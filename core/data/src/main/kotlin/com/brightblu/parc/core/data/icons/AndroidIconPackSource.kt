package com.brightblu.parc.core.data.icons

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Xml
import com.brightblu.parc.core.data.IconRasterGeometry
import com.brightblu.parc.core.data.IntBounds
import com.brightblu.parc.core.data.toIntBounds
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Production [IconPackSource]. Package discovery goes through the three public theme actions, which
 * are only visible because the module manifest declares the matching `<queries>` intents; no
 * permission and no `QUERY_ALL_PACKAGES` is involved.
 *
 * Everything here is Android-only: a pack's appfilter is compiled XML in its resources (some packs
 * also ship `assets/appfilter.xml`), and its drawables are rendered from the pack's own Resources.
 */
internal class AndroidIconPackSource(private val context: Context) : IconPackSource<Bitmap> {
    private val packageManager = context.packageManager

    override fun packs(): List<IconPack> {
        val found = ArrayList<IconPack>()
        ThemeActions.forEach { action ->
            val resolved = try {
                packageManager.queryIntentActivities(Intent(action), 0)
            } catch (e: Exception) {
                emptyList()
            }
            resolved.forEach { info ->
                val activity = info.activityInfo ?: return@forEach
                val packageName = activity.packageName
                if (packageName == context.packageName) return@forEach
                val application = activity.applicationInfo ?: return@forEach
                val label = try {
                    application.loadLabel(packageManager).toString().trim()
                } catch (e: Exception) {
                    ""
                }
                found.add(IconPack(packageName, label.ifEmpty { packageName }))
            }
        }
        return IconPackRules.distinctSorted(found)
    }

    override fun appFilter(packageName: String): Map<String, String> {
        val resources = resourcesOf(packageName) ?: return emptyMap()
        val tags = readResourceFilter(resources, packageName) ?: readAssetFilter(resources)
        return tags?.let(AppFilterParser::parse) ?: emptyMap()
    }

    override fun drawable(packageName: String, drawableName: String, sizePx: Int): Bitmap? {
        if (sizePx <= 0) return null
        val resources = resourcesOf(packageName) ?: return null
        val id = try {
            resources.getIdentifier(drawableName, "drawable", packageName)
        } catch (e: Exception) {
            0
        }
        if (id == 0) return null
        val drawable = try {
            resources.getDrawable(id, null)
        } catch (e: Exception) {
            null
        } ?: return null
        return render(drawable, sizePx)
    }

    private fun resourcesOf(packageName: String): Resources? = try {
        packageManager.getResourcesForApplication(packageName)
    } catch (e: Exception) {
        null
    }

    private fun readResourceFilter(resources: Resources, packageName: String): List<AppFilterTag>? {
        val id = try {
            resources.getIdentifier(FilterResourceName, "xml", packageName)
        } catch (e: Exception) {
            0
        }
        if (id == 0) return null
        return try {
            val parser = resources.getXml(id)
            try {
                readTags(parser)
            } finally {
                parser.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readAssetFilter(resources: Resources): List<AppFilterTag>? {
        val stream = openAsset(resources, FilterFileName) ?: openAsset(resources, FilterAssetName)
            ?: return null
        return try {
            stream.use {
                val parser = Xml.newPullParser()
                parser.setInput(it, null)
                readTags(parser)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openAsset(resources: Resources, name: String): InputStream? = try {
        resources.assets.open(name)
    } catch (e: Exception) {
        null
    }

    private fun readTags(parser: XmlPullParser): List<AppFilterTag> {
        val tags = ArrayList<AppFilterTag>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val attributes = LinkedHashMap<String, String>()
                for (index in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(index) ?: continue
                    attributes[name] = parser.getAttributeValue(index) ?: ""
                }
                tags.add(AppFilterTag(parser.name ?: "", attributes))
            }
            event = parser.next()
        }
        return tags
    }

    /**
     * Pack artwork is already drawn to fill its square, so a legacy drawable is drawn full-bleed;
     * only adaptive layers need the 108 dp canvas scaled down to its 72 dp safe zone.
     */
    private fun render(drawable: Drawable, sizePx: Int): Bitmap? {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            val bounds = IconRasterGeometry.adaptiveLayerBounds(sizePx).toIntBounds()
            drawable.background?.let { drawLayer(canvas, it, bounds) }
            drawable.foreground?.let { drawLayer(canvas, it, bounds) }
        } else {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
        }
        return bitmap
    }

    private fun drawLayer(canvas: Canvas, drawable: Drawable, bounds: IntBounds) {
        drawable.setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        drawable.draw(canvas)
    }

    private companion object {
        /** Public theme actions declared by the three pack formats the card names. */
        val ThemeActions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
        )
        const val FilterResourceName = "appfilter"
        const val FilterFileName = "appfilter.xml"
        const val FilterAssetName = "appfilter"
    }
}
