# WebView 注入的 JS 接口通过反射调用，必须保留方法名
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# 保留行号，崩溃日志仍可定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
