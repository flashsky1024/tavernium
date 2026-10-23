# WebView 的 JavascriptInterface 方法由 JS 通过反射调用，R8 静态分析感知不到，必须保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
