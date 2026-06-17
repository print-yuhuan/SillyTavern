# Release 开启 R8 代码压缩/资源压缩/混淆（isMinifyEnabled = true）。
# Compose / AndroidX / kotlinx-coroutines 由各自随库分发的 consumer 规则覆盖；
# Activity/Service 由 Manifest keep 规则保留。此处仅需补 snakeyaml。

# snakeyaml 通过反射构造/内省对象，保留以保证 config.yaml 读写不被破坏。
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
