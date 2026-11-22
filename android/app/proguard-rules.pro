# ML Kit's code scanner reaches these by reflection; R8 renaming them crashes the scanner at launch.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
# Must stay in android.print to reach the print callbacks' package-private constructors.
-keep class android.print.PdfFile** { *; }

# Glance builds widget click callbacks by reflection from their class name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
