# ══════════════════════════════════════════════════════
# 晚安成电killer · R8 混淆规则（L0 加固，2026-10-17）
# ══════════════════════════════════════════════════════

# ── 1) WebView JS 桥：地图选点 ──
# map.html 通过 Android.onPick(lat, lng) 字符串反射调用，
# 方法名与 @JavascriptInterface 注解必须保留，否则地图选点失效。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── 2) 自定义 View：PowerToggleView ──
# 由 XML 布局反射实例化（AGP 通常会自动生成 keep，这里双保险）。
-keep class com.gnuestc.checkin.PowerToggleView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ── 3) 调试信息：保留行号，混淆后崩溃堆栈仍可定位 ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 备注 ──
# · Activity / Receiver 等清单组件由 AGP 自动保留，无需手写；
# · OkHttp / 协程 / AndroidX 自带 consumer 规则，无需配置；
# · 以后若新增「字符串反射调用」的代码（JS 桥、动态加载、序列化），需在此补充规则。
