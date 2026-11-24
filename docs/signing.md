# Signing

Never commit the keystore or a password. If the keystore is lost, a later APK cannot install over the old one.

Create it once, on a machine you control. Pick the passwords yourself. They do not go in this repo.

```
keytool -genkeypair -v \
  -keystore tunareceive-release.keystore \
  -alias tunareceive \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TunaReceive, O=TunaReceive, C=US"
```

`keytool` will ask for the store password and the key password. Keep a backup of `tunareceive-release.keystore` outside the repo and outside GitHub.

GitHub Actions secrets on `bigtunafish9943/TunaReceive`:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 tunareceive-release.keystore` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `tunareceive` |
| `KEY_PASSWORD` | the key password |

Releases are public. The APK downloads without a GitHub login. The secrets do not.

Until those four exist, CI still builds a test APK signed with a throwaway key that exists only on the runner. That APK can be sideloaded. It will not upgrade in place to the real release. Uninstall it first.
