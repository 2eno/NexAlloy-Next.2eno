package io.github.nexalloy.`2eno`.interpals.misc

import app.twoeno.extension.interpals.DownloadPhotosPatch
import io.github.nexalloy.patch

val DownloadPhotos = patch(
    name = "Download photos",
    description = "Adds \"Download photo\" to the menu of the photo viewer.",
) {
    DownloadPhotosPatch.install(appContext)
}
