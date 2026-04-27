package software.nhs.fhirvalidator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.utilities.npm.NpmPackage;

import software.nhs.fhirvalidator.util.FhirUtils;
import software.nhs.fhirvalidator.util.OperationOutcomeUtils;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class MessageDefinitionApplier {

    private final List<MessageDefinition> messageDefinitions;
    Logger log = LoggerFactory.getLogger(MessageDefinitionApplier.class);

    public MessageDefinitionApplier(
            ImplementationGuideParser implementationGuideParser,
            List<NpmPackage> npmPackages) {
        this.messageDefinitions = npmPackages.stream()
                .flatMap(packageItem -> {
                    try {
                        return implementationGuideParser.getResourcesOfType(packageItem, MessageDefinition.class)
                                .stream();
                    } catch (IOException ex) {
                        log.error(ex.getMessage(), ex);
                        throw new RuntimeException("error in MessageDefinitionApplier", ex);
                    }
                })
                .collect(Collectors.toList());
    }

    public OperationOutcome applyMessageDefinition(IBaseResource resource) {
        if (!(resource instanceof Bundle _resource) || (_resource.getType() != Bundle.BundleType.MESSAGE)) {
            return null;
        }

        MessageHeader messageHeader = findMessageHeader((Bundle) resource);
        if (messageHeader == null) {
            return OperationOutcomeUtils.createOperationOutcome("No MessageHeader found.", "Bundle.entry");
        }

        Coding messageType = messageHeader.getEventCoding();
        String messageDefinitionProfile = messageHeader.getDefinition();
        MessageDefinition messageDefinition = findMessageDefinition(messageType, messageDefinitionProfile);

        if (messageDefinition == null) {
            return OperationOutcomeUtils.createOperationOutcome(
                    "Unsupported message type " + messageType.getSystem() + "#" + messageType.getCode() + ".",
                    "MessageHeader.eventCoding");
        }

        return applyMessageDefinition((Bundle) resource, messageDefinition);
    }

    private MessageHeader findMessageHeader(Bundle bundle) {
        return bundle.getEntry()
                .stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(MessageHeader.class::isInstance)
                .map(MessageHeader.class::cast)
                .findFirst()
                .orElse(null);
    }

    private MessageDefinition findMessageDefinition(Coding messageType, String messageDefinitionProfile) {
        if (messageDefinitionProfile != null) {
            return messageDefinitions.stream()
                    .filter(def -> messageType.getSystem().equals(def.getEventCoding().getSystem()) &&
                            messageType.getCode().equals(def.getEventCoding().getCode()) &&
                            messageDefinitionProfile.equals(def.getUrl()))
                    .findFirst()
                    .orElse(null);
        } else {
            return messageDefinitions.stream()
                    .filter(def -> messageType.getSystem().equals(def.getEventCoding().getSystem()) &&
                            messageType.getCode().equals(def.getEventCoding().getCode()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private OperationOutcome applyMessageDefinition(Bundle resource, MessageDefinition messageDefinition) {
        List<OperationOutcome.OperationOutcomeIssueComponent> issues = messageDefinition.getFocus()
                .stream()
                .map(focus -> applyMessageDefinitionFocus(resource, focus))
                .filter(OperationOutcome.OperationOutcomeIssueComponent.class::isInstance)
                .map(OperationOutcome.OperationOutcomeIssueComponent.class::cast)
                .collect(Collectors.toList());

        if (issues.isEmpty()) {
            return null;
        }

        return OperationOutcomeUtils.createOperationOutcome(issues);
    }

    private OperationOutcome.OperationOutcomeIssueComponent applyMessageDefinitionFocus(Bundle bundle,
            MessageDefinition.MessageDefinitionFocusComponent focus) {
        List<IBaseResource> matchingResources = FhirUtils.getResourcesOfType(bundle, focus.getCode());
        applyMessageDefinitionFocusProfile(focus, matchingResources);
        return applyMessageDefinitionFocusMinMax(focus, matchingResources.size());
    }

    private void applyMessageDefinitionFocusProfile(MessageDefinition.MessageDefinitionFocusComponent focus,
            List<IBaseResource> matchingResources) {
        if (focus.hasProfile()) {
            FhirUtils.applyProfile(matchingResources, focus.getProfileElement());
        }
    }

    private OperationOutcome.OperationOutcomeIssueComponent applyMessageDefinitionFocusMinMax(
            MessageDefinition.MessageDefinitionFocusComponent focus, int resourceCount) {
        String resourceType = focus.getCode();

        if (focus.hasMin()) {
            int min = focus.getMin();
            if (resourceCount < min) {
                return OperationOutcomeUtils.createOperationOutcomeIssue(
                        "Bundle contains too few resources of type " + resourceType + ". Expected at least " + min
                                + ".",
                        "Bundle.entry");
            }
        }

        if (focus.hasMax()) {
            String maxStr = focus.getMax();
            if (!maxStr.equals("*")) {
                int max = Integer.parseInt(maxStr);
                if (resourceCount > max) {
                    return OperationOutcomeUtils.createOperationOutcomeIssue(
                            "Bundle contains too many resources of type " + resourceType + ". Expected at most " + max
                                    + ".",
                            "Bundle.entry");
                }
            }
        }

        return null;
    }
}
