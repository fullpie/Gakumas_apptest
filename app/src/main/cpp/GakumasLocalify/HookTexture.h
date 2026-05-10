#ifndef GAKUMAS_LOCALIFY_HOOK_TEXTURE_H
#define GAKUMAS_LOCALIFY_HOOK_TEXTURE_H

#include <string>

namespace GakumasLocal::HookMain
{
    void* ReplaceTextureOrSpriteAsset(void* result, const std::string& assetName);
    void* ReplaceTextureOrSpriteByObjectName(void* result);
    void ReplaceAllAssetTextures(void* allAssets);
    void* ReplaceSpriteAssetByTextureName(void* sprite);
    void* ReplaceSpriteTexture(void* texture2D);

    void* ResolveSpriteGetTextureHookAddress();
    void* ResolveAssetBundleLoadAssetHookAddress();
    void* ResolveAssetBundleLoadAssetAsyncHookAddress();
    void* ResolveAssetBundleRequestResultHookAddress();
    void* ResolveAssetBundleRequestAssetHookAddress();
    void* ResolveAssetBundleRequestAllAssetsHookAddress();
    void* ResolveResourcesLoadHookAddress();
}

#endif // GAKUMAS_LOCALIFY_HOOK_TEXTURE_H
