#include "HookTexture.h"

#include "Log.h"
#include "Il2cppUtils.hpp"
#include "Local.h"
#include "config/Config.hpp"
#include "../deps/UnityResolve/UnityResolve.hpp"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <exception>
#include <filesystem>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

namespace GakumasLocal::HookMain
{
    using Il2cppString = UnityResolve::UnityType::String;

    extern void* (*Sprite_get_texture_Orig)(void* self);

    bool IsNativeObjectAlive(void* obj);

    Il2cppUtils::Il2CppClassHead* Texture2DClass = nullptr;
    Il2cppUtils::Il2CppClassHead* SpriteClass = nullptr;
    std::unordered_map<std::string, uint32_t> LoadedLocalTextureHandles{};
    std::unordered_set<std::string> AppliedLocalTextureKeys{};

    Il2cppUtils::Il2CppClassHead* GetTexture2DClass() {
        if (!Texture2DClass) {
            const auto textureClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "Texture2D");
            if (textureClass) {
                Texture2DClass = static_cast<Il2cppUtils::Il2CppClassHead*>(textureClass->address);
            }
        }
        return Texture2DClass;
    }

    Il2cppUtils::Il2CppClassHead* GetSpriteClass() {
        if (!SpriteClass) {
            const auto spriteClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "Sprite");
            if (spriteClass) {
                SpriteClass = static_cast<Il2cppUtils::Il2CppClassHead*>(spriteClass->address);
            }
        }
        return SpriteClass;
    }

    bool IsTexture2D(void* obj) {
        const auto textureClass = GetTexture2DClass();
        if (!obj || !textureClass) return false;

        const auto objClass = Il2cppUtils::get_class_from_instance(obj);
        if (objClass == textureClass) return true;

        return UnityResolve::Invoke<bool>("il2cpp_class_is_assignable_from", textureClass, objClass);
    }

    bool IsSprite(void* obj) {
        const auto spriteClass = GetSpriteClass();
        if (!obj || !spriteClass) return false;

        const auto objClass = Il2cppUtils::get_class_from_instance(obj);
        if (objClass == spriteClass) return true;

        return UnityResolve::Invoke<bool>("il2cpp_class_is_assignable_from", spriteClass, objClass);
    }

    Il2cppString* GetObjectName(void* obj) {
        if (!obj) return nullptr;

        static auto Object_GetName = reinterpret_cast<Il2cppString * (*)(void*)>(
            Il2cppUtils::il2cpp_resolve_icall("UnityEngine.Object::GetName(UnityEngine.Object)"));
        return Object_GetName ? Object_GetName(obj) : nullptr;
    }

    void SetDontUnloadUnusedAsset(void* obj) {
        if (!obj) return;

        static auto Object_set_hideFlags = reinterpret_cast<void (*)(void*, int)>(
            Il2cppUtils::GetMethodPointer("UnityEngine.CoreModule.dll", "UnityEngine", "Object", "set_hideFlags"));
        if (Object_set_hideFlags) {
            Object_set_hideFlags(obj, 32);
        }
    }

    void AddTexturePathCandidate(std::vector<std::filesystem::path>& candidates, const std::filesystem::path& path) {
        if (path.empty()) return;
        if (std::find(candidates.begin(), candidates.end(), path) == candidates.end()) {
            candidates.emplace_back(path);
        }
        if (!path.has_extension()) {
            auto pngPath = path;
            pngPath += ".png";
            if (std::find(candidates.begin(), candidates.end(), pngPath) == candidates.end()) {
                candidates.emplace_back(std::move(pngPath));
            }
        }
    }

    enum class TextureCategory {
        Image,
        Atlas,
        Others,
    };

    std::string ToLowerAscii(std::string value) {
        std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
            return static_cast<char>(std::tolower(ch));
        });
        return value;
    }

    TextureCategory GetTextureCategory(const std::string& textureName) {
        const auto lowerName = ToLowerAscii(std::filesystem::path(textureName).filename().generic_string());
        if (lowerName.rfind("img", 0) == 0) {
            return TextureCategory::Image;
        }
        if (lowerName.rfind("sactx", 0) == 0) {
            return TextureCategory::Atlas;
        }
        return TextureCategory::Others;
    }

    std::filesystem::path GetTextureCategoryDirName(TextureCategory category) {
        switch (category) {
        case TextureCategory::Image:
            return "image";
        case TextureCategory::Atlas:
            return "atlas";
        default:
            return "others";
        }
    }

    std::filesystem::path GetTextureReplaceRoot() {
        return Local::GetBasePath() / "texture2d";
    }

    std::filesystem::path GetTextureDumpRoot() {
        return Local::GetBasePath() / "dump-files" / "texture2d";
    }

    std::filesystem::path GetTextureReplaceBase(const std::string& textureName) {
        return GetTextureReplaceRoot() / GetTextureCategoryDirName(GetTextureCategory(textureName));
    }

    std::filesystem::path GetTextureDumpBase(const std::string& textureName) {
        return GetTextureDumpRoot() / GetTextureCategoryDirName(GetTextureCategory(textureName));
    }

    std::vector<std::string> SplitString(const std::string& value, char delimiter) {
        std::vector<std::string> parts;
        size_t start = 0;
        while (start <= value.size()) {
            const auto end = value.find(delimiter, start);
            parts.emplace_back(value.substr(start, end == std::string::npos ? std::string::npos : end - start));
            if (end == std::string::npos) break;
            start = end + 1;
        }
        return parts;
    }

    void AppendTextureCandidates(std::vector<std::filesystem::path>& target, std::vector<std::filesystem::path>&& source);
    std::string NormalizeLocalAssetKey(const std::filesystem::path& path);

    bool IsHexHashPart(const std::string& value) {
        return value.size() == 8 && std::all_of(value.begin(), value.end(), [](unsigned char ch) {
            return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
        });
    }

    std::string GetPortableSactxTextureName(const std::string& objectName) {
        auto fileName = std::filesystem::path(objectName).filename().generic_string();
        if (fileName.ends_with(".png")) {
            fileName.resize(fileName.size() - 4);
        }

        const auto parts = SplitString(fileName, '-');
        if (parts.size() < 5 || parts[0] != "sactx" || parts[2].find('x') == std::string::npos) {
            return {};
        }

        const auto atlasEnd = IsHexHashPart(parts.back()) ? parts.size() - 1 : parts.size();
        if (atlasEnd <= 4) return {};

        std::string portableName = parts[0] + "-" + parts[1] + "-" + parts[2];
        for (size_t i = 4; i < atlasEnd; ++i) {
            portableName += "-" + parts[i];
        }
        return portableName;
    }

    std::unordered_map<std::string, std::unordered_map<std::string, std::vector<std::filesystem::path>>> RecursiveTexturePathIndex{};

    std::vector<std::filesystem::path> GetRecursiveTextureCandidates(const std::filesystem::path& basePath,
        const std::string& lookupName) {
        std::vector<std::filesystem::path> candidates;
        if (lookupName.empty() || !std::filesystem::exists(basePath)) return candidates;

        const auto baseKey = NormalizeLocalAssetKey(basePath);
        auto& index = RecursiveTexturePathIndex[baseKey];
        if (index.empty()) {
            for (const auto& entry : std::filesystem::recursive_directory_iterator(basePath)) {
                if (!entry.is_regular_file()) continue;

                const auto& path = entry.path();
                if (ToLowerAscii(path.extension().generic_string()) != ".png") continue;

                const auto fileName = path.filename().generic_string();
                const auto stemName = path.stem().generic_string();
                index[fileName].emplace_back(path);
                if (stemName != fileName) {
                    index[stemName].emplace_back(path);
                }
            }
        }

        if (const auto iter = index.find(lookupName); iter != index.end()) {
            candidates.insert(candidates.end(), iter->second.begin(), iter->second.end());
        }
        return candidates;
    }

    std::vector<std::filesystem::path> GetNamedTextureCandidates(const std::filesystem::path& assetName) {
        std::vector<std::filesystem::path> candidates;
        if (assetName.empty()) return candidates;

        const auto basePath = GetTextureReplaceBase(assetName.filename().generic_string());
        AddTexturePathCandidate(candidates, basePath / assetName);
        if (assetName.has_parent_path()) {
            AddTexturePathCandidate(candidates, basePath / assetName.filename());
        }
        AppendTextureCandidates(candidates, GetRecursiveTextureCandidates(basePath, assetName.filename().generic_string()));

        const auto portableAssetName = GetPortableSactxTextureName(assetName.filename().generic_string());
        if (!portableAssetName.empty()) {
            AddTexturePathCandidate(candidates, basePath / portableAssetName);
            AppendTextureCandidates(candidates, GetRecursiveTextureCandidates(basePath, portableAssetName));
        }
        return candidates;
    }

    std::vector<std::filesystem::path> GetSpriteTextureCandidates(const std::string& objectName) {
        std::vector<std::filesystem::path> candidates;
        if (objectName.empty()) return candidates;

        auto safeObjectName = objectName;
        std::replace(safeObjectName.begin(), safeObjectName.end(), '|', '_');

        const auto basePath = GetTextureReplaceBase(safeObjectName);
        AddTexturePathCandidate(candidates, basePath / objectName);
        if (safeObjectName != objectName) {
            AddTexturePathCandidate(candidates, basePath / safeObjectName);
        }
        AppendTextureCandidates(candidates, GetRecursiveTextureCandidates(basePath, safeObjectName));
        const auto portableObjectName = GetPortableSactxTextureName(safeObjectName);
        if (!portableObjectName.empty() && portableObjectName != objectName && portableObjectName != safeObjectName) {
            AddTexturePathCandidate(candidates, basePath / portableObjectName);
            AppendTextureCandidates(candidates, GetRecursiveTextureCandidates(basePath, portableObjectName));
        }
        return candidates;
    }

    void AppendTextureCandidates(std::vector<std::filesystem::path>& target, std::vector<std::filesystem::path>&& source) {
        target.insert(target.end(),
            std::make_move_iterator(source.begin()),
            std::make_move_iterator(source.end()));
    }

    std::vector<std::filesystem::path> GetSpriteAssetTextureCandidates(void* sprite, const std::string& assetName) {
        std::vector<std::filesystem::path> candidates;

        const auto assetPath = std::filesystem::path(assetName);
        if (!assetName.empty()) {
            AppendTextureCandidates(candidates, GetSpriteTextureCandidates(assetPath.filename().generic_string()));
        }

        if (sprite && Sprite_get_texture_Orig) {
            if (const auto texture = Sprite_get_texture_Orig(sprite)) {
                if (const auto textureName = GetObjectName(texture)) {
                    AppendTextureCandidates(candidates, GetSpriteTextureCandidates(textureName->ToString()));
                }
            }
        }
        return candidates;
    }

    std::string NormalizeLocalAssetKey(const std::filesystem::path& path) {
        auto key = path.lexically_normal().generic_string();
        std::replace(key.begin(), key.end(), '\\', '/');
        return key;
    }

    std::string SanitizeDumpPathPart(std::string part) {
        constexpr std::string_view invalidChars = "<>:\"/\\|?*";
        if (part.empty() || part == "." || part == "..") return "_";

        for (auto& ch : part) {
            if (static_cast<unsigned char>(ch) < 32 || invalidChars.find(ch) != std::string_view::npos) {
                ch = '_';
            }
        }

        while (!part.empty() && (part.back() == '.' || part.back() == ' ')) {
            part.back() = '_';
        }
        return part.empty() ? "_" : part;
    }

    std::filesystem::path SanitizeDumpSubPath(const std::filesystem::path& dumpSubDir) {
        std::filesystem::path safePath;
        for (const auto& part : dumpSubDir) {
            const auto partString = part.generic_string();
            if (partString.empty() || partString == "." || partString == ".."
                || part == part.root_name() || part == part.root_directory()) {
                continue;
            }
            safePath /= SanitizeDumpPathPart(partString);
        }
        return safePath;
    }

    bool DumpTexture2D(void* texture2D) {
        if (!IsTexture2D(texture2D)) return false;

        const auto objectName = GetObjectName(texture2D);
        const auto textureName = objectName ? objectName->ToString() : std::string("texture");
        const auto dumpDir = GetTextureDumpBase(textureName);
        const auto dumpPath = dumpDir / (SanitizeDumpPathPart(textureName) + ".png");

        if (std::filesystem::exists(dumpPath)) return true;

        static auto Texture2D_get_width = [] {
            const auto textureClass = GetTexture2DClass();
            const auto method = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, "get_width", 0) : nullptr;
            return method ? reinterpret_cast<int (*)(void*)>(method->methodPointer) : nullptr;
        }();
        static auto Texture2D_get_height = [] {
            const auto textureClass = GetTexture2DClass();
            const auto method = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, "get_height", 0) : nullptr;
            return method ? reinterpret_cast<int (*)(void*)>(method->methodPointer) : nullptr;
        }();
        static auto Texture2D_ctor = [] {
            const auto textureClass = GetTexture2DClass();
            const auto ctor = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, ".ctor", 2) : nullptr;
            return ctor ? reinterpret_cast<void (*)(void*, int, int)>(ctor->methodPointer) : nullptr;
        }();
        static auto Texture2D_ReadPixels = [] {
            const auto textureClass = GetTexture2DClass();
            const auto method = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, "ReadPixels", 3) : nullptr;
            return method ? reinterpret_cast<void (*)(void*, UnityResolve::UnityType::Rect, int, int)>(method->methodPointer) : nullptr;
        }();
        static auto Texture2D_Apply = [] {
            const auto textureClass = GetTexture2DClass();
            const auto method = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, "Apply", 0) : nullptr;
            return method ? reinterpret_cast<void (*)(void*)>(method->methodPointer) : nullptr;
        }();
        static auto RenderTexture_GetTemporary = [] {
            const auto renderTextureClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "RenderTexture");
            const auto method = renderTextureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(renderTextureClass->address, "GetTemporary", 3) : nullptr;
            return method ? reinterpret_cast<void* (*)(int, int, int)>(method->methodPointer) : nullptr;
        }();
        static auto RenderTexture_ReleaseTemporary = [] {
            const auto renderTextureClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "RenderTexture");
            const auto method = renderTextureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(renderTextureClass->address, "ReleaseTemporary", 1) : nullptr;
            return method ? reinterpret_cast<void (*)(void*)>(method->methodPointer) : nullptr;
        }();
        static auto RenderTexture_get_active = [] {
            const auto renderTextureClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "RenderTexture");
            const auto method = renderTextureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(renderTextureClass->address, "get_active", 0) : nullptr;
            return method ? reinterpret_cast<void* (*)()>(method->methodPointer) : nullptr;
        }();
        static auto RenderTexture_set_active = [] {
            const auto renderTextureClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "RenderTexture");
            const auto method = renderTextureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(renderTextureClass->address, "set_active", 1) : nullptr;
            return method ? reinterpret_cast<void (*)(void*)>(method->methodPointer) : nullptr;
        }();
        static auto Graphics_Blit = [] {
            const auto graphicsClass = Il2cppUtils::GetClass("UnityEngine.CoreModule.dll", "UnityEngine", "Graphics");
            const auto method = graphicsClass ? Il2cppUtils::il2cpp_class_get_method_from_name(graphicsClass->address, "Blit", 2) : nullptr;
            return method ? reinterpret_cast<void (*)(void*, void*)>(method->methodPointer) : nullptr;
        }();
        static auto ImageConversion_EncodeToPNG = [] {
            using EncodeToPNGFn = void* (*)(void*);
            if (const auto icall = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.ImageConversion::EncodeToPNG(UnityEngine.Texture2D)")) {
                return reinterpret_cast<EncodeToPNGFn>(icall);
            }

            for (const auto& assemblyName : {"UnityEngine.ImageConversionModule.dll", "UnityEngine.CoreModule.dll"}) {
                const auto assembly = UnityResolve::Get(assemblyName);
                const auto imageConversionClass = assembly ? assembly->Get("ImageConversion", "UnityEngine") : nullptr;
                const auto method = imageConversionClass
                    ? Il2cppUtils::il2cpp_class_get_method_from_name(imageConversionClass->address, "EncodeToPNG", 1)
                    : nullptr;
                if (method) {
                    return reinterpret_cast<EncodeToPNGFn>(method->methodPointer);
                }
            }
            return static_cast<EncodeToPNGFn>(nullptr);
        }();
        static auto File_WriteAllBytes = [] {
            const auto fileClass = Il2cppUtils::GetClass("mscorlib.dll", "System.IO", "File");
            const auto method = fileClass ? Il2cppUtils::il2cpp_class_get_method_from_name(fileClass->address, "WriteAllBytes", 2) : nullptr;
            return method ? reinterpret_cast<void (*)(Il2cppString*, void*)>(method->methodPointer) : nullptr;
        }();

        if (!Texture2D_get_width || !Texture2D_get_height || !Texture2D_ctor || !Texture2D_ReadPixels
            || !Texture2D_Apply || !RenderTexture_GetTemporary || !RenderTexture_ReleaseTemporary
            || !RenderTexture_get_active || !RenderTexture_set_active || !Graphics_Blit
            || !ImageConversion_EncodeToPNG || !File_WriteAllBytes) {
            Log::Error("DumpTexture2D failed: Unity texture dump API not found.");
            return false;
        }

        const auto width = Texture2D_get_width(texture2D);
        const auto height = Texture2D_get_height(texture2D);
        if (width <= 0 || height <= 0) return false;

        void* renderTexture = nullptr;
        void* readableTexture = nullptr;
        void* previousActive = nullptr;
        const auto cleanup = [&] {
            if (RenderTexture_get_active && RenderTexture_set_active
                && (previousActive || RenderTexture_get_active() == renderTexture)) {
                RenderTexture_set_active(previousActive);
            }
            if (renderTexture && RenderTexture_ReleaseTemporary) {
                RenderTexture_ReleaseTemporary(renderTexture);
            }
        };

        try {
            std::filesystem::create_directories(dumpDir);

            renderTexture = RenderTexture_GetTemporary(width, height, 0);
            if (!renderTexture) {
                cleanup();
                return false;
            }

            Graphics_Blit(texture2D, renderTexture);
            previousActive = RenderTexture_get_active();
            RenderTexture_set_active(renderTexture);

            readableTexture = UnityResolve::Invoke<void*>("il2cpp_object_new", GetTexture2DClass());
            if (!readableTexture) {
                cleanup();
                return false;
            }

            Texture2D_ctor(readableTexture, width, height);
            Texture2D_ReadPixels(readableTexture, UnityResolve::UnityType::Rect(0, 0, static_cast<float>(width), static_cast<float>(height)), 0, 0);
            Texture2D_Apply(readableTexture);

            const auto pngBytes = ImageConversion_EncodeToPNG(readableTexture);
            if (!pngBytes) {
                cleanup();
                return false;
            }

            File_WriteAllBytes(Il2cppString::New(dumpPath.string()), pngBytes);
            Log::InfoFmt("Texture dumped: %s", dumpPath.string().c_str());
            cleanup();
            return true;
        }
        catch (const std::exception& ex) {
            cleanup();
            Log::ErrorFmt("DumpTexture2D failed: %s", ex.what());
            return false;
        }
        catch (...) {
            cleanup();
            Log::Error("DumpTexture2D failed: unknown error.");
            return false;
        }
    }

    void DumpTextureOrSpriteAsset(void* result) {
        if (!result) return;

        if (IsTexture2D(result)) {
            DumpTexture2D(result);
            return;
        }
        if (IsSprite(result) && Sprite_get_texture_Orig) {
            if (const auto texture = Sprite_get_texture_Orig(result)) {
                DumpTexture2D(texture);
            }
        }
    }

    void* LoadLocalTexture2D(const std::filesystem::path& path) {
        if (!std::filesystem::is_regular_file(path)) return nullptr;

        const auto cacheKey = NormalizeLocalAssetKey(path);
        if (const auto iter = LoadedLocalTextureHandles.find(cacheKey); iter != LoadedLocalTextureHandles.end()) {
            const auto cachedTexture = UnityResolve::Invoke<void*>("il2cpp_gchandle_get_target", iter->second);
            if (cachedTexture && IsNativeObjectAlive(cachedTexture)) {
                return cachedTexture;
            }

            UnityResolve::Invoke<void>("il2cpp_gchandle_free", iter->second);
            LoadedLocalTextureHandles.erase(iter);
        }

        const auto textureClass = GetTexture2DClass();
        if (!textureClass) return nullptr;

        static auto Texture2D_ctor = [] {
            const auto textureClass = GetTexture2DClass();
            const auto ctor = textureClass ? Il2cppUtils::il2cpp_class_get_method_from_name(textureClass, ".ctor", 2) : nullptr;
            return ctor ? reinterpret_cast<void (*)(void*, int, int)>(ctor->methodPointer) : nullptr;
        }();
        static auto ImageConversion_LoadImage = [] {
            using LoadImageFn = bool (*)(void*, void*, bool);
            if (const auto icall = Il2cppUtils::il2cpp_resolve_icall(
                "UnityEngine.ImageConversion::LoadImage(UnityEngine.Texture2D,System.Byte[],System.Boolean)")) {
                return reinterpret_cast<LoadImageFn>(icall);
            }

            for (const auto& assemblyName : {"UnityEngine.ImageConversionModule.dll", "UnityEngine.CoreModule.dll"}) {
                const auto assembly = UnityResolve::Get(assemblyName);
                const auto imageConversionClass = assembly ? assembly->Get("ImageConversion", "UnityEngine") : nullptr;
                const auto method = imageConversionClass
                    ? Il2cppUtils::il2cpp_class_get_method_from_name(imageConversionClass->address, "LoadImage", 3)
                    : nullptr;
                if (method) {
                    return reinterpret_cast<LoadImageFn>(method->methodPointer);
                }
            }
            return static_cast<LoadImageFn>(nullptr);
        }();
        static auto File_ReadAllBytes = [] {
            const auto fileClass = Il2cppUtils::GetClass("mscorlib.dll", "System.IO", "File");
            const auto method = fileClass ? Il2cppUtils::il2cpp_class_get_method_from_name(fileClass->address, "ReadAllBytes", 1) : nullptr;
            return method ? reinterpret_cast<void* (*)(Il2cppString*)>(method->methodPointer) : nullptr;
        }();

        if (!Texture2D_ctor || !ImageConversion_LoadImage || !File_ReadAllBytes) {
            Log::Error("LoadLocalTexture2D failed: Unity Texture2D/ImageConversion/File API not found.");
            return nullptr;
        }

        const auto fileBytes = File_ReadAllBytes(Il2cppString::New(path.string()));
        if (!fileBytes) return nullptr;

        const auto texture = UnityResolve::Invoke<void*>("il2cpp_object_new", textureClass);
        Texture2D_ctor(texture, 2, 2);
        if (!ImageConversion_LoadImage(texture, fileBytes, false)) {
            Log::ErrorFmt("LoadLocalTexture2D failed: %s", path.string().c_str());
            return nullptr;
        }

        SetDontUnloadUnusedAsset(texture);
        LoadedLocalTextureHandles.emplace(cacheKey, UnityResolve::Invoke<uint32_t>("il2cpp_gchandle_new", texture, false));
        Log::InfoFmt("Texture replaced from local file: %s", path.string().c_str());
        return texture;
    }

    void* LoadLocalTexture2DFromCandidates(const std::vector<std::filesystem::path>& candidates) {
        for (const auto& candidate : candidates) {
            if (auto texture = LoadLocalTexture2D(candidate)) {
                return texture;
            }
        }
        return nullptr;
    }

    bool ApplyLocalImageToTexture2D(void* texture2D, const std::filesystem::path& path) {
        if (!IsTexture2D(texture2D) || !std::filesystem::is_regular_file(path)) return false;

        auto cacheKey = NormalizeLocalAssetKey(path)
            + "|" + std::to_string(reinterpret_cast<std::uintptr_t>(texture2D));
        if (AppliedLocalTextureKeys.contains(cacheKey)) return true;

        static auto ImageConversion_LoadImage = [] {
            using LoadImageFn = bool (*)(void*, void*, bool);
            if (const auto icall = Il2cppUtils::il2cpp_resolve_icall(
                "UnityEngine.ImageConversion::LoadImage(UnityEngine.Texture2D,System.Byte[],System.Boolean)")) {
                return reinterpret_cast<LoadImageFn>(icall);
            }

            for (const auto& assemblyName : {"UnityEngine.ImageConversionModule.dll", "UnityEngine.CoreModule.dll"}) {
                const auto assembly = UnityResolve::Get(assemblyName);
                const auto imageConversionClass = assembly ? assembly->Get("ImageConversion", "UnityEngine") : nullptr;
                const auto method = imageConversionClass
                    ? Il2cppUtils::il2cpp_class_get_method_from_name(imageConversionClass->address, "LoadImage", 3)
                    : nullptr;
                if (method) {
                    return reinterpret_cast<LoadImageFn>(method->methodPointer);
                }
            }
            return static_cast<LoadImageFn>(nullptr);
        }();
        static auto File_ReadAllBytes = [] {
            const auto fileClass = Il2cppUtils::GetClass("mscorlib.dll", "System.IO", "File");
            const auto method = fileClass ? Il2cppUtils::il2cpp_class_get_method_from_name(fileClass->address, "ReadAllBytes", 1) : nullptr;
            return method ? reinterpret_cast<void* (*)(Il2cppString*)>(method->methodPointer) : nullptr;
        }();

        if (!ImageConversion_LoadImage || !File_ReadAllBytes) {
            Log::Error("ApplyLocalImageToTexture2D failed: Unity ImageConversion/File API not found.");
            return false;
        }

        const auto fileBytes = File_ReadAllBytes(Il2cppString::New(path.string()));
        if (!fileBytes) return false;

        if (!ImageConversion_LoadImage(texture2D, fileBytes, false)) {
            Log::ErrorFmt("ApplyLocalImageToTexture2D failed: %s", path.string().c_str());
            return false;
        }

        SetDontUnloadUnusedAsset(texture2D);
        AppliedLocalTextureKeys.emplace(std::move(cacheKey));
        Log::InfoFmt("Texture replaced in-place from local file: %s", path.string().c_str());
        return true;
    }

    bool ApplyLocalImageToTexture2DFromCandidates(void* texture2D, const std::vector<std::filesystem::path>& candidates) {
        for (const auto& candidate : candidates) {
            if (ApplyLocalImageToTexture2D(texture2D, candidate)) {
                return true;
            }
        }
        return false;
    }

    bool ReplaceSpriteTextureInPlace(void* sprite, const std::vector<std::filesystem::path>& candidates) {
        if (!sprite || !Sprite_get_texture_Orig) return false;

        const auto texture = Sprite_get_texture_Orig(sprite);
        if (!IsTexture2D(texture)) return false;

        return ApplyLocalImageToTexture2DFromCandidates(texture, candidates);
    }

    void* ReplaceTextureOrSpriteAsset(void* result, const std::string& assetName) {
        if (!Config::replaceTexture && !Config::dumpRuntimeTexture) return result;

        if (Config::dumpRuntimeTexture) {
            DumpTextureOrSpriteAsset(result);
        }
        if (!Config::replaceTexture) return result;

        if (IsSprite(result)) {
            if (ReplaceSpriteTextureInPlace(result, GetSpriteAssetTextureCandidates(result, assetName))) {
                return result;
            }
            return result;
        }

        if (result && !IsTexture2D(result)) return result;

        if (auto localTexture = LoadLocalTexture2DFromCandidates(GetNamedTextureCandidates(std::filesystem::path(assetName)))) {
            return localTexture;
        }
        return result;
    }

    void* ReplaceTextureOrSpriteByObjectName(void* result) {
        if ((!Config::replaceTexture && !Config::dumpRuntimeTexture) || !result) return result;

        const auto objectName = GetObjectName(result);
        if (!objectName) return result;

        const auto assetPath = std::filesystem::path(objectName->ToString());
        if (Config::dumpRuntimeTexture) {
            DumpTextureOrSpriteAsset(result);
        }
        if (!Config::replaceTexture) return result;

        if (IsSprite(result)) {
            std::vector<std::filesystem::path> candidates;
            AppendTextureCandidates(candidates, GetSpriteTextureCandidates(objectName->ToString()));
            if (ReplaceSpriteTextureInPlace(result, candidates)) {
                return result;
            }
            return result;
        }

        if (IsTexture2D(result)) {
            if (auto localTexture = LoadLocalTexture2DFromCandidates(GetNamedTextureCandidates(assetPath))) {
                return localTexture;
            }
        }

        return result;
    }

    void ReplaceAllAssetTextures(void* allAssets) {
        if ((!Config::replaceTexture && !Config::dumpRuntimeTexture) || !allAssets) return;

        auto assets = reinterpret_cast<UnityResolve::UnityType::Array<void*>*>(allAssets);
        for (std::uintptr_t i = 0; i < assets->max_length; ++i) {
            auto asset = assets->At(static_cast<unsigned int>(i));
            auto replacedAsset = ReplaceTextureOrSpriteByObjectName(asset);
            if (replacedAsset != asset) {
                assets->At(static_cast<unsigned int>(i)) = replacedAsset;
            }
        }
    }

    void* ReplaceSpriteAssetByTextureName(void* sprite) {
        if (!Config::replaceTexture || !sprite) return sprite;

        if (!IsSprite(sprite)) {
            return sprite;
        }

        if (ReplaceSpriteTextureInPlace(sprite, GetSpriteAssetTextureCandidates(sprite, ""))) {
            return sprite;
        }
        return sprite;
    }

    void* ReplaceSpriteTexture(void* texture2D) {
        if ((!Config::replaceTexture && !Config::dumpRuntimeTexture) || !IsTexture2D(texture2D)) return texture2D;

        const auto objectName = GetObjectName(texture2D);
        if (!objectName) return texture2D;

        if (Config::dumpRuntimeTexture) {
            DumpTexture2D(texture2D);
        }
        if (!Config::replaceTexture) return texture2D;

        if (ApplyLocalImageToTexture2DFromCandidates(texture2D, GetSpriteTextureCandidates(objectName->ToString()))) {
            return texture2D;
        }
        return texture2D;
    }

    void* ResolveSpriteGetTextureHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.Sprite::get_texture(UnityEngine.Sprite)")) {
            return addr;
        }
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.Sprite::get_texture()")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.CoreModule.dll", "UnityEngine", "Sprite", "get_texture");
    }

    void* ResolveAssetBundleLoadAssetHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall(
            "UnityEngine.AssetBundle::LoadAsset_Internal(System.String,System.Type)")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.AssetBundleModule.dll", "UnityEngine", "AssetBundle",
            "LoadAsset_Internal", {"System.String", "System.Type"});
    }

    void* ResolveAssetBundleLoadAssetAsyncHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall(
            "UnityEngine.AssetBundle::LoadAssetAsync_Internal(System.String,System.Type)")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.AssetBundleModule.dll", "UnityEngine", "AssetBundle",
            "LoadAssetAsync_Internal", {"System.String", "System.Type"});
    }

    void* ResolveAssetBundleRequestResultHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.AssetBundleRequest::GetResult()")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.AssetBundleModule.dll", "UnityEngine", "AssetBundleRequest", "GetResult");
    }

    void* ResolveAssetBundleRequestAssetHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.AssetBundleRequest::get_asset()")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.AssetBundleModule.dll", "UnityEngine", "AssetBundleRequest", "get_asset");
    }

    void* ResolveAssetBundleRequestAllAssetsHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall("UnityEngine.AssetBundleRequest::get_allAssets()")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.AssetBundleModule.dll", "UnityEngine", "AssetBundleRequest", "get_allAssets");
    }

    void* ResolveResourcesLoadHookAddress() {
        if (const auto addr = Il2cppUtils::il2cpp_resolve_icall(
            "UnityEngine.ResourcesAPIInternal::Load(System.String,System.Type)")) {
            return addr;
        }
        return Il2cppUtils::GetMethodPointer("UnityEngine.CoreModule.dll", "UnityEngine", "ResourcesAPIInternal",
            "Load", {"System.String", "System.Type"});
    }

}
