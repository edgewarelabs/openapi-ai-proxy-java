#!/bin/sh
DIR=$(dirname "$0")
cd $DIR/..
java -jar build/libs/openapi-ai-proxy-java.jar \
    -s ../seller-simulator/openapi-sonata/geographicAddressManagement.api.yaml \
    -s ../seller-simulator/openapi-sonata/geographicSiteManagement.api.yaml \
    -s ../seller-simulator/openapi-sonata/productCatalog.api.yaml \
    -s ../seller-simulator/openapi-sonata/productOfferingQualificationManagement.api.yaml \
    -s ../seller-simulator/openapi-sonata/productOrderManagement.api.yaml \
    -s ../seller-simulator/openapi-sonata/quoteManagement.api.yaml \
    -t http://localhost:4000 \
    -p 3000
