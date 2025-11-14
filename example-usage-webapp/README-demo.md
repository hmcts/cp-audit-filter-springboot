# Example Service - Pulls in jarfile from azure repository
The goal of this demo module is to demonstrate a project consuming the audit jarfile
It uses a jarfile from azure artifactoru
See the dependency in build.gradle

Or you may need to add package scanning to pick up the filter
@SpringBootApplication(scanBasePackages = {"uk.gov.hmcts.cp.audit"})

# Run the demo service
# Run artemis with the docker-compose.yml file ( in separate terminal windows )
docker-compose up
cd demo
gradle bootRun
curl http://localhost:8090/

# Debug the demo service
Run DemoApplication in idea in debug mode 
( i.e. right click on the java and select Debug DemoApplication.main() )
Navigate to the jar file in "External Libraries"
i.e. Find uk.gov.hmcts.cp:cp-audit-filter.jar and add a debug point in AuditFilter

curl http://localhost:8090/

# To do .... add test and run from github pipelines
