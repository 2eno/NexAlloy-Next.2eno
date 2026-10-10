package io.github.nexalloy;

/**
 * Build information read by the NexAlloy fork of the Morphe extensions.
 * <p>
 * All fields are compile-time constants, so they are inlined and this class is never loaded.
 */
public final class BuildConfig {
    public static final boolean DEBUG = io.github.nexalloy.patches.BuildConfig.DEBUG;
    public static final String VERSION_NAME = io.github.nexalloy.patches.BuildConfig.VERSION_NAME;
    public static final String PATCH_VERSION = io.github.nexalloy.patches.BuildConfig.PATCH_VERSION;

    private BuildConfig() {
    }
}
