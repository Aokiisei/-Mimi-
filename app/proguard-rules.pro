# WebView 注入的 JS 接口通过反射调用，必须保留方法名
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# 保留行号，崩溃日志仍可定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 学习通签到：ZXing 二维码解码内部会按格式反射创建 Reader
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
