package software.nhs.fhirvalidator.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.google.gson.JsonSyntaxException;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.validation.FhirValidator;
import software.nhs.fhirvalidator.configuration.ValidatorConfiguration;
import software.nhs.fhirvalidator.service.CapabilityStatementApplier;
import software.nhs.fhirvalidator.service.ImplementationGuideParser;
import software.nhs.fhirvalidator.service.MessageDefinitionApplier;
import software.nhs.fhirvalidator.util.OperationOutcomeUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is a wrapper around the HAPI FhirValidator.
 * The FhirValidator is built using default settings and the available
 * implementation guides are loaded into it.
 */

public class ValidateController {
    private ValidatorConfiguration validatorConfiguration;
    private FhirValidator validator;
    private FhirContext fhirContext;
    private ImplementationGuideParser implementationGuideParser;
    private CapabilityStatementApplier capabilityStatementApplier;
    private MessageDefinitionApplier messageDefinitionApplier;

    Logger log = LogManager.getLogger(ValidateController.class);

    public ValidateController(String PROFILE_MANIFEST_FILE) {
        validatorConfiguration = new ValidatorConfiguration(PROFILE_MANIFEST_FILE);
        validator = validatorConfiguration.validator;
        fhirContext = validatorConfiguration.fhirContext;
        implementationGuideParser = new ImplementationGuideParser(
            fhirContext);
        capabilityStatementApplier = new CapabilityStatementApplier(
            implementationGuideParser,
            validatorConfiguration.npmPackages);
        messageDefinitionApplier = new MessageDefinitionApplier(
            implementationGuideParser, validatorConfiguration.npmPackages);
    }
    public String validate(String input) {
        OperationOutcome result = parseAndValidateResource(input);
        return fhirContext.newJsonParser().encodeResourceToString(result);
    }

    public OperationOutcome parseAndValidateResource(String input) {

        try {
            IBaseResource inputResource = fhirContext.newJsonParser().parseResource(input);
            List<IBaseResource> resources = getResourcesToValidate(inputResource);

            List<OperationOutcome> operationOutcomeList = resources.stream()
                    .map(this::validateResource)
                    .collect(Collectors.toList());

            List<OperationOutcomeIssueComponent> operationOutcomeIssues = operationOutcomeList.stream()
                    .filter(Objects::nonNull)
                    .flatMap(operationOutcome -> operationOutcome.getIssue().stream())
                    .collect(Collectors.toList());

            return OperationOutcomeUtils.createOperationOutcome(operationOutcomeIssues);
        } catch (JsonSyntaxException | NullPointerException | IllegalArgumentException | InvalidRequestException
                | DataFormatException e) {
            log.error(e.toString());
            return OperationOutcomeUtils
                    .createOperationOutcome((e.getMessage() != null && !(e instanceof NullPointerException))
                    ? e.getMessage() : "Invalid JSON", null);
        }
    }

    private OperationOutcome validateResource(IBaseResource resource) {
        capabilityStatementApplier.applyCapabilityStatementProfiles(resource);
        OperationOutcome messageDefinitionErrors = messageDefinitionApplier.applyMessageDefinition(resource);
        if (messageDefinitionErrors != null) {
            return messageDefinitionErrors;
        }
        return (OperationOutcome) validator.validateWithResult(resource).toOperationOutcome();
    }

    private List<IBaseResource> getResourcesToValidate(IBaseResource inputResource) {
        if (inputResource == null) {
            return new ArrayList<>();
        }

        if (inputResource instanceof Bundle _inputResource && (_inputResource.getType() == Bundle.BundleType.SEARCHSET)) {
            List<IBaseResource> bundleResources = new ArrayList<>();
            for (Bundle.BundleEntryComponent entry : ((Bundle) inputResource).getEntry()) {
                bundleResources.add(entry.getResource());
            }

            return bundleResources;
        }

        return Arrays.asList(inputResource);
    }
}
