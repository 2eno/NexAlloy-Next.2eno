package io.github.nexalloy.piko.instagram.download

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.widget.ListView
import android.widget.Toast
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Shows a menu in Instagram's own dialog, like Piko's download options, or in a plain dialog when the dialog builder
 * of Instagram is not found.
 */
internal class InstagramDialog(
    private val build: Method?,
    private val title: Field?,
    private val cancelable: Method?,
    private val canceledOnTouchOutside: Method?,
    private val negativeButton: Method?,
    private val menuBottomCorners: Field?,
    private val log: (String) -> Unit,
) {
    private val builderClass = build?.declaringClass
    private val constructor = builderClass?.runCatching { getDeclaredConstructor(Context::class.java) }?.getOrNull()
    private val menu = builderClass?.declaredMethods?.singleOrNull { method ->
        method.returnType == Void.TYPE && method.parameterTypes.contentEquals(
            arrayOf(DialogInterface.OnClickListener::class.java, Array<CharSequence>::class.java)
        )
    }

    init {
        listOfNotNull(build, cancelable, canceledOnTouchOutside, negativeButton, menu, constructor, title, menuBottomCorners)
            .forEach { it.isAccessible = true }
    }

    fun show(activity: Activity, title: String, options: List<Pair<String, () -> Unit>>) {
        val labels = options.map { it.first }.toTypedArray<CharSequence>()
        val onClick = DialogInterface.OnClickListener { _, which ->
            try {
                options[which].second()
            } catch (e: Exception) {
                log("${options[which].first} failed: ${e.stackTraceToString().take(1500)}")
                Toast.makeText(activity, "${options[which].first} failed", Toast.LENGTH_SHORT).show()
            }
        }
        val dialog = runCatching { native(activity, title, labels, onClick) }
            .onFailure { log("Instagram's dialog failed, showing a plain one: $it") }
            .getOrNull()
            ?: AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(labels, onClick)
                .setNegativeButton(CLOSE) { dialog, _ -> dialog.dismiss() }
                .create()
        dialog.show()
    }

    private fun native(
        activity: Activity,
        titleText: String,
        labels: Array<CharSequence>,
        onClick: DialogInterface.OnClickListener,
    ): Dialog? {
        if (build == null || constructor == null || menu == null) return null
        val builder = constructor.newInstance(activity)
        title?.set(builder, titleText)
        menu.invoke(builder, onClick, labels)
        negativeButton?.invoke(builder, DialogInterface.OnClickListener { dialog, _ -> dialog.dismiss() }, CLOSE)
        cancelable?.invoke(builder, true)
        canceledOnTouchOutside?.invoke(builder, true)
        val dialog = build.invoke(builder) as Dialog
        if (negativeButton != null) clearMenuBottomCorners(dialog)
        return dialog
    }

    /**
     * The builder rounds the last menu row even when the close button follows it.
     */
    private fun clearMenuBottomCorners(dialog: Dialog) {
        val field = menuBottomCorners ?: return
        runCatching {
            val adapter = dialog.findViewById<ListView>(android.R.id.list)?.adapter ?: return
            if (field.declaringClass.isInstance(adapter)) field.setBoolean(adapter, false)
        }.onFailure { log("could not clear the menu corners: $it") }
    }

    private companion object {
        const val CLOSE = "Close"
    }
}
