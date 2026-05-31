# 当前 release 未开启代码压缩（isMinifyEnabled = false），此文件预留。
# 若后续开启 R8/混淆，需为 snakeyaml 反射与 Compose 增补 keep 规则。

# snakeyaml 通过反射构造对象
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
