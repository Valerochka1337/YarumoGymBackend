package tech.valerochkagym.controller.publicweb

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AssetLinksController {
  @GetMapping("/.well-known/assetlinks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
  fun assetLinks() =
    listOf(
      mapOf(
        "relation" to listOf("delegate_permission/common.handle_all_urls"),
        "target" to
          mapOf(
            "namespace" to "android_app",
            "package_name" to "com.valerochka1337.valerochkagym",
            "sha256_cert_fingerprints" to
              listOf(
                "A0:C2:6E:8E:34:44:DF:72:C9:4A:1D:27:EB:17:84:7D:5A:10:4C:0E:B4:C4:DC:C5:18:0F:2F:32:76:9A:1A:61"
              ),
          ),
      )
    )
}
