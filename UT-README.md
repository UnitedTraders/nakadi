# Deploy to nexus

Add to `~/.gradle/gradle.properties` your Crowd credentials:

```
auroraUser=...
auroraPassword=...
```

Then run

```
./gradlew -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true build uploadArchives -x jar -x test

```
