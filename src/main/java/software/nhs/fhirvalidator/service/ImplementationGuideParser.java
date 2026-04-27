package software.nhs.fhirvalidator.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class ImplementationGuideParser {

    private final FhirContext fhirContext;

    Logger log = LoggerFactory.getLogger(ImplementationGuideParser.class);

    public ImplementationGuideParser(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    public <T extends Resource> List<T> getResourcesOfType(NpmPackage npmPackage, Class<T> resourceType)
            throws IOException {
        IParser jsonParser = fhirContext.newJsonParser();
        return npmPackage.listResources(resourceType.getSimpleName())
                .stream()
                .map(t -> {
                    try {
                        return npmPackage.loadResource(t);
                    } catch (IOException ex) {
                        log.error(ex.getMessage(), ex);
                        throw new RuntimeException("error in getResourcesOfTypes", ex);
                    }
                })
                .map(jsonParser::parseResource)
                .filter(resourceType::isInstance)
                .map(resourceType::cast)
                .collect(Collectors.toList());
    }
}
