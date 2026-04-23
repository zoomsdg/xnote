build： gradle assembleRelease ，not gradlew or ./gradlew
keystore generated.
keytool -genkeypair -v \
  -keystore /home/cc/myprojects/my-release-key.keystore \
  -alias myalias \        # ← use whatever alias your build.gradle expects
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

two key points need to be remembered.
