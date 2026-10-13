# BouncyCastle's provider names its algorithm classes in strings and makes
# them by reflection: these two packages keep their names. The rest of the
# library, the larger part, is shrunk and renamed.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# sshj looks the provider up by name.
-keepnames class org.bouncycastle.jce.provider.BouncyCastleProvider

# The LDAP certificate stores of BouncyCastle: Android has no javax.naming,
# and ssh never asks for them.
-dontwarn javax.naming.**
