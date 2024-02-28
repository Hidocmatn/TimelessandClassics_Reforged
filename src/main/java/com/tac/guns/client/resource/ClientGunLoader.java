package com.tac.guns.client.resource;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tac.guns.GunMod;
import com.tac.guns.client.animation.gltf.AnimationStructure;
import com.tac.guns.client.model.BedrockGunModel;
import com.tac.guns.client.resource.cache.ClientAssetManager;
import com.tac.guns.client.resource.cache.data.ClientGunInfo;
import com.tac.guns.client.resource.pojo.ClientGunInfoPOJO;
import com.tac.guns.client.resource.pojo.animation.gltf.RawAnimationStructure;
import com.tac.guns.client.resource.pojo.display.GunDisplay;
import com.tac.guns.client.resource.pojo.display.GunModelTexture;
import com.tac.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tac.guns.client.resource.pojo.model.BedrockVersion;
import com.tac.guns.client.resource.pojo.model.CubesItem;
import com.tac.guns.client.resource.texture.ZipPackTexture;
import com.tac.guns.util.GetJarResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ClientGunLoader {
    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(ResourceLocation.class, new ResourceLocation.Serializer()).registerTypeAdapter(CubesItem.class, new CubesItem.Deserializer()).create();
    /**
     * 放置自定义枪械模型的目录
     */
    public static final Path FOLDER = Paths.get("config", GunMod.MOD_ID, "custom");
    /**
     * 如果从zip包中加载、注册材质，自动在命名空间后加上此后缀，以作区分。
     */
    public static final String ZIP_TEXTURE_NAMESPACE_SUFFIX = "-zip";
    private static final Marker MARKER = MarkerManager.getMarker("BedrockModelLoader");
    private static final String DEFAULT_TEXTURE_NAME = "default";
    private static final String DEFAULT_GUN_PACK_NAME = "tac_default_gun.zip";
    private static final Pattern GUNS_PATTERN = Pattern.compile("^(\\w+)/index/(\\w+)\\.json$");
    /**
     * 缓存已经注册的材质的注册名，避免重复注册浪费内存。
     */
    private static final Set<ResourceLocation> TMP_REGISTER_TEXTURE = Sets.newHashSet();

    public static void initAndReload() {
        TMP_REGISTER_TEXTURE.clear();
        createFolder();
        checkDefaultPack();
        readZipFiles();
    }

    public static void readZipGunPack(File file) {
        try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> iteration = zipFile.entries();
            while (iteration.hasMoreElements()) {
                String path = iteration.nextElement().getName();
                loadGunFromZipPack(path, zipFile);
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public static void loadGunFromZipPack(String path, ZipFile zipFile) throws IOException {
        Matcher matcher = GUNS_PATTERN.matcher(path);
        if (matcher.find()) {
            String namespace = matcher.group(1);
            String id = matcher.group(2);
            ZipEntry entry = zipFile.getEntry(path);
            if (entry == null) {
                GunMod.LOGGER.warn(MARKER, "{} file don't exist", path);
                return;
            }
            try (InputStream stream = zipFile.getInputStream(entry)) {
                // 获取枪械的定义文件
                ClientGunInfoPOJO infoPOJO = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), ClientGunInfoPOJO.class);
                ClientGunInfo info = new ClientGunInfo();
                info.setName(infoPOJO.getName());
                info.setTooltip(infoPOJO.getTooltip());
                ClientAssetManager.INSTANCE.setGunInfo(new ResourceLocation(namespace, id), info);
                // 加载Display数据
                loadGunDisplayData(namespace, id, path, zipFile, infoPOJO.getDisplay());
            }
        }
    }

    /**
     * @return 材质的实际注册名
     */
    public static ResourceLocation registerZipTexture(String zipFilePath, ResourceLocation texturePath) {
        ResourceLocation registryName = new ResourceLocation(texturePath.getNamespace() + ZIP_TEXTURE_NAMESPACE_SUFFIX, texturePath.getPath());
        if (!TMP_REGISTER_TEXTURE.contains(registryName)) {
            ZipPackTexture zipPackTexture = new ZipPackTexture(zipFilePath, texturePath.getNamespace(), texturePath.getPath());
            if (zipPackTexture.isExist()) {
                Minecraft.getInstance().textureManager.register(registryName, zipPackTexture);
                TMP_REGISTER_TEXTURE.add(registryName);
            }
        }
        return registryName;
    }

    private static void loadGunDisplayData(String namespace, String id, String path, ZipFile zipFile, GunDisplay displayData) {
        // 检查默认材质是否存在，并创建默认的RenderType
        Optional<GunModelTexture> defaultOptional = displayData.getModelTextures().stream().filter(texture -> DEFAULT_TEXTURE_NAME.equals(texture.getName())).findAny();
        if (defaultOptional.isEmpty()) {
            GunMod.LOGGER.warn(MARKER, "{} meta file don't have default texture", path);
            return;
        }
        ResourceLocation defaultTextureRegistry = registerZipTexture(zipFile.getName(), new ResourceLocation(namespace, defaultOptional.get().getLocation()));
        RenderType renderType = RenderType.itemEntityTranslucentCull(defaultTextureRegistry);
        // 注册所有需要的材质
        displayData.getModelTextures().forEach(texture -> {
            ResourceLocation registryName = new ResourceLocation(namespace, texture.getLocation());
            ResourceLocation textureName = registerZipTexture(zipFile.getName(), registryName);
            // todo ClientAssetManager.INSTANCE.setZipTextures(registryName, textureName);
        });
        // 加载模型
        BedrockGunModel model = loadGunModel(namespace, id, zipFile, displayData, renderType);
        // 加载动画
        if (model != null) loadAnimation(namespace, id, zipFile, displayData, model);
    }

    private static BedrockGunModel loadGunModel(String namespace, String id, ZipFile zipFile, GunDisplay displayData, RenderType renderType) {
        String modelPath = String.format("%s/models/%s", namespace, displayData.getModelLocation());
        ZipEntry modelEntry = zipFile.getEntry(modelPath);
        if (modelEntry == null) {
            GunMod.LOGGER.warn(MARKER, "{} model file don't exist", modelPath);
            return null;
        }
        try (InputStream modelFileStream = zipFile.getInputStream(modelEntry)) {
            BedrockModelPOJO pojo = GSON.fromJson(new InputStreamReader(modelFileStream, StandardCharsets.UTF_8), BedrockModelPOJO.class);
            // 先判断是不是 1.10.0 版本基岩版模型文件
            if (pojo.getFormatVersion().equals(BedrockVersion.LEGACY.getVersion())) {
                // 如果 model 字段不为空
                if (pojo.getGeometryModelLegacy() != null) {
                    return new BedrockGunModel(pojo, BedrockVersion.LEGACY, renderType);
                } else {
                    // 否则日志给出提示
                    GunMod.LOGGER.warn(MARKER, "{} model file don't have model field", modelPath);
                }
                return null;
            }

            // 判定是不是 1.12.0 版本基岩版模型文件
            if (pojo.getFormatVersion().equals(BedrockVersion.NEW.getVersion())) {
                // 如果 model 字段不为空
                if (pojo.getGeometryModelNew() != null) {
                    return new BedrockGunModel(pojo, BedrockVersion.NEW, renderType);
                } else {
                    // 否则日志给出提示
                    GunMod.LOGGER.warn(MARKER, "{} model file don't have model field", modelPath);
                }
                return null;
            }

            GunMod.LOGGER.warn(MARKER, "{} model version is not 1.10.0 or 1.12.0", modelPath);
        } catch (IOException ioe) {
            // 可能用来判定错误，打印下
            GunMod.LOGGER.warn(MARKER, "Failed to load model: {}", modelPath);
            ioe.printStackTrace();
        }
        return null;
    }

    private static void loadAnimation(String namespace, String id, ZipFile zipFile, GunDisplay displayData, BedrockGunModel model) {
        if (displayData.getAnimationLocation() == null) return;
        String animationPath = String.format("%s/animations/%s", namespace, displayData.getAnimationLocation());
        ZipEntry modelEntry = zipFile.getEntry(animationPath);
        if (modelEntry == null) {
            GunMod.LOGGER.warn(MARKER, "{} animation file don't exist", animationPath);
            return;
        }
        try (InputStream animationFileStream = zipFile.getInputStream(modelEntry)) {
            RawAnimationStructure rawStructure = GSON.fromJson(new InputStreamReader(animationFileStream, StandardCharsets.UTF_8), RawAnimationStructure.class);
            AnimationStructure structure = new AnimationStructure(rawStructure);
            ClientAssetManager.INSTANCE.setBedrockAnimatedAsset(new ResourceLocation(namespace, id), model, structure);
        } catch (IOException ioe) {
            // 可能用来判定错误，打印下
            GunMod.LOGGER.warn(MARKER, "Failed to load animation: {}", animationPath);
            ioe.printStackTrace();
        }
    }

    private static void checkDefaultPack() {
        // 不管存不存在，强行覆盖
        String jarDefaultPackPath = String.format("/assets/%s/custom/%s", GunMod.MOD_ID, DEFAULT_GUN_PACK_NAME);
        GetJarResources.copyModFile(jarDefaultPackPath, FOLDER, DEFAULT_GUN_PACK_NAME);
    }

    private static void readZipFiles() {
        File[] files = FOLDER.toFile().listFiles((dir, name) -> true);
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".zip")) {
                readZipGunPack(file);
            }
        }
    }

    private static void createFolder() {
        File folder = FOLDER.toFile();
        if (!folder.isDirectory()) {
            try {
                Files.createDirectories(folder.toPath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
