# Deploy to private-nexus

mvn deploy:deploy-file \ 
  -DgroupId=com.unitedtraders.luna \
  -DartifactId=nakadi \
  -Dversion=1.0.0-R2016_10_04_UT_CUSTOM \
  -Dpackaging=jar \
  -Dfile=build/libs/nakadi.jar \
  -DrepositoryId=aurora-release \
  -Durl={release-repos-url}