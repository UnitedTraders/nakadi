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

To run specific migration:
```
java -Dloader.main=org.zalando.nakadi.Aruha771MigrationHelper -cp build/libs/nakadi.jar org.springframework.boot.loader.PropertiesLauncher 'jdbc:postgresql://localhost:5432/local_nakadi_db' nakadi nakadi
```