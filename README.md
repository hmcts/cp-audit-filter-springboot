# Audit requirements

* Audit access of every REST endpoint
* Audit activity captures request and response payloads.  For every single interaction with a REST endpoint, 
we will have at least one and at most two audit activities (if response has a payload)

Audit request payload captures
* Path and query parameters
* Specific request header identifying the action performed (ACCEPT or CONTENT-TYPE header value)
* Request body

Audit response payload captures
* Specific request header identifying the action performed (ACCEPT or CONTENT-TYPE header value)
* Response body


# Queries / Issues
Why add the complexity of OpenApi ? Surely we just want to log the url headers and bodies?

THe opinionated solution relies on Open API specification to decipher path parameters (as key / value pairs). 
It's possible that other services may rely on a different approach to identifying the same and hence 
an alternate implementation for uk.gov.hmcts.cp.filter.audit.parser.RestApiParser can be supplied


# Testing
We want to confirm that when we hit any endpoint, we get the audit message sent to artemis jms
We create a dummy spring boot application which inherits the default AuditFilter
We create a dummy root endpoint which we can send a message to
We spin up activemq-artemis in docker container with anonymous enabled
We create a JmsMessageListener that listen to the same topic and pass this to our mock service

Thus we can verify the content of messages that have been sent via activemq

Note that we could view the messages in artemis console on http://0.0.0.0:8161/console/artemis
... although if we consume them in our TestJmsListener the queues will be empty
