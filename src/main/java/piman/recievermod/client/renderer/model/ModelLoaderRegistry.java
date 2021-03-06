/*
 * Minecraft Forge
 * Copyright (c) 2016-2019.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package piman.recievermod.client.renderer.model;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.item.Item;
import net.minecraftforge.client.model.ICustomModelLoader;
import net.minecraftforge.client.model.ItemLayerModel;
import net.minecraftforge.client.model.ModelLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.model.IUnbakedModel;
import net.minecraft.client.renderer.model.ModelResourceLocation;
import net.minecraft.resources.IReloadableResourceManager;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.animation.ITimeValue;
import net.minecraftforge.common.model.animation.AnimationStateMachine;
import net.minecraftforge.common.model.animation.IAnimationStateMachine;

/**
 * Central hub for custom model loaders.
 */
public class ModelLoaderRegistry {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Set<ICustomModelLoader> loaders = Sets.newHashSet();
    private static final Map<ResourceLocation, IUnbakedModel> cache = Maps.newHashMap();
    private static final Deque<ResourceLocation> loadingModels = Queues.newArrayDeque();

    private static final Map<Item, ResourceLocation> registeredItems = new HashMap<>();

    private static ModelBakery bakery;

    private static IResourceManager manager;

    // Forge built-in loaders
//    public static void init()
//    {
//        registerLoader(B3DLoader.INSTANCE);
//        registerLoader(OBJLoader.INSTANCE);
//        registerLoader(ModelFluid.FluidLoader.INSTANCE);
//        registerLoader(ItemLayerModel.Loader.INSTANCE);
//        registerLoader(MultiLayerModel.Loader.INSTANCE);
//        registerLoader(ModelDynBucket.LoaderDynBucket.INSTANCE);
//    }

    /**
     * Makes system aware of your loader.
     */
    public static void registerLoader(ICustomModelLoader loader) {
        loaders.add(loader);
        ((IReloadableResourceManager) Minecraft.getInstance().getResourceManager()).addReloadListener(loader);
        // FIXME: Existing model loaders expect to receive a call as soon as they are registered, which was the old behaviour pre-1.13
        // without this, their manager field is never initialized.
        loader.onResourceManagerReload(Minecraft.getInstance().getResourceManager());
    }

    public static void registerItems(Map<Item, ResourceLocation> map) {
        registeredItems.putAll(map);
    }

    public static boolean loaded(ResourceLocation location) {
        return cache.containsKey(location);
    }

    public static IUnbakedModel getLoaded(ResourceLocation location) {
        return cache.get(location);
    }

    public static Map<ResourceLocation, IUnbakedModel> getUnbakedModels() {
        return ImmutableMap.copyOf(cache);
    }

    public static ResourceLocation getActualLocation(ResourceLocation location) {
        if (location instanceof ModelResourceLocation) return location;
        if (location.getPath().startsWith("builtin/")) return location;
        return new ResourceLocation(location.getNamespace(), "models/" + location.getPath());
    }

    /**
     * Primary method to get IModel instances.
     *
     * @param location The path to load, either:
     *                 - Pure {@link ResourceLocation}. "models/" will be prepended to the path, then
     *                 the path is passed to the {@link ICustomModelLoader}s, which may further modify
     *                 the path before asking resource packs for it. For example, the {@link ModelLoader.VanillaLoader}
     *                 appends ".json" before looking the model up.
     *                 - {@link ModelResourceLocation}. The blockstate system will load the model, using {@link ModelLoader.VariantLoader}.
     */
    public static IUnbakedModel getModel(Map.Entry<ResourceLocation, Item> entry) throws Exception {
        IUnbakedModel model;

        IUnbakedModel cached = cache.get(new ModelResourceLocation(entry.getKey(), "inventory"));
        if (cached != null) return cached;

        ResourceLocation location = entry.getValue() == null ? new ModelResourceLocation(entry.getKey(), "inventory") : registeredItems.get(entry.getValue());

        for (ResourceLocation loading : loadingModels) {
            if (location.getClass() == loading.getClass() && location.equals(loading)) {
                throw new LoaderException("circular model dependencies, stack: [" + Joiner.on(", ").join(loadingModels) + "]");
            }
        }
        loadingModels.addLast(location);
        try {
            ResourceLocation actual = getActualLocation(location);
            ICustomModelLoader accepted = null;
            for (ICustomModelLoader loader : loaders) {
                try {
                    if (loader.accepts(actual)) {
                        if (accepted != null) {
                            throw new LoaderException(String.format("2 loaders (%s and %s) want to load the same model %s", accepted, loader, location));
                        }
                        accepted = loader;
                    }
                } catch (Exception e) {
                    throw new LoaderException(String.format("Exception checking if model %s can be loaded with loader %s, skipping", location, loader), e);
                }
            }

            if (accepted == null) {
                if (bakery == null) {
                    bakery = new ModelLoader(manager, new AtlasTexture("textures"), Minecraft.getInstance().getBlockColors(), Minecraft.getInstance().getProfiler());
                }
                model = bakery.getUnbakedModel(actual);
                //throw new LoaderException("no suitable loader found for the model " + location + ", skipping");
            }
            else {
                try {
                    model = accepted.loadModel(actual);
                }
                catch (Exception e) {
                    throw new LoaderException(String.format("Exception loading model %s with loader %s, skipping", location, accepted), e);
                }
                if (model == getMissingModel()) {
                    throw new LoaderException(String.format("Loader %s returned missing model while loading model %s", accepted, location));
                }
                if (model == null) {
                    throw new LoaderException(String.format("Loader %s returned null while loading model %s", accepted, location));
                }
            }
        } finally {
            ResourceLocation popLoc = loadingModels.removeLast();
            if (popLoc != location) {
                throw new IllegalStateException("Corrupted loading model stack: " + popLoc + " != " + location);
            }
        }
        cache.put(new ModelResourceLocation(entry.getKey(), "inventory"), model);
//        for (ResourceLocation dep : model.getDependencies()) {
//            getModelOrMissing(new Entry<ResourceLocation, Item>() {
//				@Override
//				public Item setValue(Item value) {
//					return null;
//				}
//				
//				@Override
//				public Item getValue() {
//					return null;
//				}
//				
//				@Override
//				public ResourceLocation getKey() {
//					return dep;
//				}
//			});
//        }
        return model;
    }

    /**
     * Use this if you don't care about the exception and want some model anyway.
     */
    public static IUnbakedModel getModelOrMissing(Map.Entry<ResourceLocation, Item> entry) {
        if (entry.getValue() == null || registeredItems.containsKey(entry.getValue())) {
            try {
                return getModel(entry);
            }
            catch (Exception e) {
                return getMissingModel(new ModelResourceLocation(entry.getKey(), "inventory"), e);
            }
        }
        return null;
    }

    /**
     * Use this if you want the model, but need to log the error.
     */
    public static IUnbakedModel getModelOrLogError(Map.Entry<ResourceLocation, Item> entry, String error) {
        if (registeredItems.containsKey(entry.getValue())) {
            try {
                return getModel(entry);
            }
            catch (Exception e) {
                LOGGER.error(error, e);
                return getMissingModel(new ModelResourceLocation(entry.getKey(), "inventory"), e);
            }
        }
        return null;
    }

    public static IUnbakedModel getMissingModel() {
        return net.minecraftforge.client.model.ModelLoaderRegistry.getMissingModel();
    }

    static IUnbakedModel getMissingModel(ResourceLocation location, Throwable cause) {
        return getMissingModel();
    }

    public static void clearModelCache(IResourceManager manager) {
        ModelLoaderRegistry.manager = manager;
        bakery = new NormalModelLoader(manager, Minecraft.getInstance().getTextureMap(), Minecraft.getInstance().getBlockColors(), true);
        loaders.clear();
        cache.clear();
        // putting the builtin models in
        cache.put(new ResourceLocation("minecraft:builtin/generated"), ItemLayerModel.INSTANCE);
        cache.put(new ResourceLocation("minecraft:block/builtin/generated"), ItemLayerModel.INSTANCE);
        cache.put(new ResourceLocation("minecraft:item/builtin/generated"), ItemLayerModel.INSTANCE);
    }

    public static class LoaderException extends Exception {
        public LoaderException(String message) {
            super(message);
        }

        public LoaderException(String message, Throwable cause) {
            super(message, cause);
        }

        private static final long serialVersionUID = 1L;
    }

    public static IAnimationStateMachine loadASM(ResourceLocation location, ImmutableMap<String, ITimeValue> customParameters) {
        return AnimationStateMachine.load(manager, location, customParameters);
    }
}