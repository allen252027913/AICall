package me.fleey.ppocrv5.call

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCallAccessibilityManifestTest {
  @Test
  fun `manifest declares call capabilities and screenshot API network access`() {
    val document = parse(manifestFile())
    val permissions = document.getElementsByTagName("uses-permission").let { nodes ->
      (0 until nodes.length).mapNotNull { index ->
        nodes.item(index).androidAttribute("name")
      }.toSet()
    }
    assertEquals(
      setOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.INTERNET",
      ),
      permissions,
    )
    assertTrue(permissions.contains("android.permission.INTERNET"))
    assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
    val storage = document.getElementsByTagName("uses-permission").let { nodes ->
      (0 until nodes.length).map(nodes::item)
        .single { it.androidAttribute("name") == "android.permission.WRITE_EXTERNAL_STORAGE" }
    }
    assertEquals("28", storage.androidAttribute("maxSdkVersion"))

    val microphone = document.getElementsByTagName("uses-feature").let { nodes ->
      (0 until nodes.length).map(nodes::item)
        .single { it.androidAttribute("name") == "android.hardware.microphone" }
    }
    assertEquals("true", microphone.androidAttribute("required"))
  }

  @Test
  fun `manifest locks activity and foreground service lifecycle contracts`() {
    val document = parse(manifestFile())
    val activities = document.getElementsByTagName("activity").let { nodes ->
      (0 until nodes.length).map(nodes::item)
    }
    val mainActivity = activities.single { it.androidAttribute("name") == ".MainActivity" }
    assertEquals("true", mainActivity.androidAttribute("exported"))
    assertEquals("true", mainActivity.androidAttribute("supportsPictureInPicture"))
    assertEquals("@style/Theme.PPOCRv5Android", mainActivity.androidAttribute("theme"))
    assertEquals(
      setOf("orientation", "screenSize", "smallestScreenSize", "screenLayout"),
      mainActivity.androidAttribute("configChanges")?.split('|')?.toSet(),
    )

    val services = document.getElementsByTagName("service").let { nodes ->
      (0 until nodes.length).map(nodes::item)
    }
    assertEquals(2, services.size)
    val voiceCall = services.single { it.androidAttribute("name") == ".call.VoiceCallService" }
    assertEquals("false", voiceCall.androidAttribute("exported"))
    assertEquals("microphone|mediaProjection", voiceCall.androidAttribute("foregroundServiceType"))
    assertEquals(services.size, services.map { it.androidAttribute("name") }.distinct().size)
    assertFalse(
      (activities + services).mapNotNull { it.androidAttribute("name") }.any { name ->
        listOf("com.alibaba", "com.taobao", "com.tmall").any(name::contains)
      },
    )
  }

  @Test
  fun `main activity theme has no action bar`() {
    val themes = sequenceOf(
      File("src/main/res/values/themes.xml"),
      File("app/src/main/res/values/themes.xml"),
    ).first(File::isFile)
    val document = parse(themes)
    val theme = document.getElementsByTagName("style").let { nodes ->
      (0 until nodes.length).map(nodes::item)
        .single { it.attributes.getNamedItem("name")?.nodeValue == "Theme.PPOCRv5Android" }
    }
    assertEquals("android:Theme.Material.Light.NoActionBar", theme.attributes.getNamedItem("parent")?.nodeValue)
  }

  @Test
  fun `accessibility service is exported for system binding and permission protected`() {
    val document = parse(manifestFile())
    val services = document.getElementsByTagName("service")
    val service = (0 until services.length)
      .map(services::item)
      .first { node ->
        node.androidAttribute("name")
          ?.endsWith("LocalCallAccessibilityService") == true
      }

    assertEquals(
      "true",
      service.androidAttribute("exported"),
    )
    assertEquals(
      "android.permission.BIND_ACCESSIBILITY_SERVICE",
      service.androidAttribute("permission"),
    )
  }

  @Test
  fun `manifest exposes automation app visibility and permits screenshot recognition`() {
    val document = parse(manifestFile())
    val queryPackages = document.getElementsByTagName("queries").let { queries ->
      (0 until queries.length).flatMap { queryIndex ->
        val children = queries.item(queryIndex).childNodes
        (0 until children.length).mapNotNull { childIndex ->
          children.item(childIndex).takeIf { it.nodeName == "package" }
            ?.androidAttribute("name")
        }
      }
    }
    val permissions = document.getElementsByTagName("uses-permission").let { nodes ->
      (0 until nodes.length).mapNotNull { index ->
        nodes.item(index).androidAttribute("name")
      }
    }

    assertEquals(listOf("com.jingdong.app.mall", "com.tencent.mm"), queryPackages)
    assertTrue(permissions.contains("android.permission.INTERNET"))
  }

  @Test
  fun `accessibility config remains unrestricted to support all apps`() {
    val config = sequenceOf(
      File("src/main/res/xml/local_call_accessibility_service.xml"),
      File("app/src/main/res/xml/local_call_accessibility_service.xml"),
    ).first(File::isFile)
    val root = parse(config).documentElement

    assertFalse(root.hasAttributeNS(ANDROID_NAMESPACE, "packageNames"))
    assertEquals("true", root.getAttributeNS(ANDROID_NAMESPACE, "canRetrieveWindowContent"))
    assertEquals("true", root.getAttributeNS(ANDROID_NAMESPACE, "canPerformGestures"))
    assertTrue(root.getAttributeNS(ANDROID_NAMESPACE, "accessibilityFlags").split('|').contains("flagReportViewIds"))
    assertTrue(
      root.getAttributeNS(ANDROID_NAMESPACE, "accessibilityEventTypes")
        .split('|')
        .contains("typeViewFocused"),
    )
  }

  private fun manifestFile() = sequenceOf(
    File("src/main/AndroidManifest.xml"),
    File("app/src/main/AndroidManifest.xml"),
  ).first(File::isFile)

  private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = true
  }.newDocumentBuilder().parse(file)

  private fun org.w3c.dom.Node.androidAttribute(name: String): String? =
    attributes?.getNamedItemNS(ANDROID_NAMESPACE, name)?.nodeValue

  private companion object {
    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
  }
}
