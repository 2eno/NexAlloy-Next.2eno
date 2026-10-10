import org.gradle.api.provider.Property

interface NexAlloyBundleExtension {
    /** Fully qualified name of the class implementing `io.github.nexalloy.PatchBundle`. */
    val entry: Property<String>

    /** Shown in the NexAlloy manager. */
    val author: Property<String>

    /** Resource name of a string describing the bundle, shown in the NexAlloy manager. */
    val descriptionResource: Property<String>

    /**
     * Package id of the bundle's resources, 0x02 to 0x7e. Bundles loaded into the same app need
     * distinct ids, and must not use the app's 0x7f. NexAlloy refuses to install a bundle whose id
     * collides with an installed bundle.
     */
    val resourcePackageId: Property<Int>
}
