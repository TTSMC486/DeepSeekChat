# 保留 @JavascriptInterface 注解的方法（R8 不得重命名/删除，否则 JS 桥失效）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface,*Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.ttsmc.deepseekchat.MainActivity$Bridge { *; }
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
