# shiroikuma-doksho: classes reached only by name.
# SkinDrawable subclasses are inflated from XML (<drawable class="...">) — R8 cannot see that use.
-keep public class shiroikuma.doksho.SkinDrawable { *; }
-keep public class shiroikuma.doksho.SkinDrawable$* { public <init>(); *; }
