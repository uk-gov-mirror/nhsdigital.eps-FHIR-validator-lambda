package software.nhs.fhirvalidator.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

import org.hl7.fhir.common.hapi.validation.support.CachingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.ElementDefinition;
import org.hl7.fhir.r4.model.StructureDefinition;
import org.hl7.fhir.utilities.npm.NpmPackage;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.ConceptValidationOptions;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.util.ClasspathUtil;
import ca.uhn.fhir.validation.FhirValidator;
import software.nhs.fhirvalidator.models.SimplifierPackage;
import software.nhs.fhirvalidator.util.ResourceUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is a wrapper around the HAPI FhirValidator.
 * The FhirValidator is built using default settings and the available
 * implementation guides are loaded into it.
 */

public class ValidatorConfiguration {
    private String PROFILE_MANIFEST_FILE;
    public final FhirValidator validator;
    public final FhirContext fhirContext;
    public final List<NpmPackage> npmPackages = new ArrayList<>();

    Logger log = LoggerFactory.getLogger(ValidatorConfiguration.class);

    public ValidatorConfiguration(String _PROFILE_MANIFEST_FILE) {
        PROFILE_MANIFEST_FILE = _PROFILE_MANIFEST_FILE;
        fhirContext = FhirContext.forR4();

        // Create a chain that will hold our modules
        ValidationSupportChain supportChain = new ValidationSupportChain(
                new DefaultProfileValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext),
                terminologyValidationSupport(fhirContext),
                new SnapshotGeneratingValidationSupport(fhirContext));

        SimplifierPackage[] packages = getPackages();
        NpmPackageValidationSupport npmPackageSupport = new NpmPackageValidationSupport(fhirContext);

        try {
            for (SimplifierPackage individualPackage : packages) {
                String packagePath = String.format("classpath:package/%s-%s.tgz", individualPackage.packageName,
                        individualPackage.version);
                npmPackageSupport.loadPackageFromClasspath(packagePath);
                try (InputStream is = ClasspathUtil.loadResourceAsStream(packagePath)) {
                    NpmPackage pkg = NpmPackage.fromPackage(is);
                    npmPackages.add(pkg);
                }
            }
        } catch (InternalErrorException | IOException ex) {
            log.error(ex.getMessage(), ex);
            throw new RuntimeException("error loading simplifier packages", ex);
        }
        supportChain.addValidationSupport(npmPackageSupport);
        generateSnapshots(supportChain);
        supportChain.fetchCodeSystem("http://snomed.info/sct");

        CachingValidationSupport validationSupport = new CachingValidationSupport(supportChain);

        // Create a validator using the FhirInstanceValidator module.
        FhirInstanceValidator validatorModule = new FhirInstanceValidator(validationSupport);
        validator = fhirContext.newValidator().registerValidatorModule(validatorModule);
    }

    private void generateSnapshots(IValidationSupport supportChain) {
        List<StructureDefinition> structureDefinitions = supportChain.fetchAllStructureDefinitions();
        if (structureDefinitions == null) {
            return;
        }

        ValidationSupportContext context = new ValidationSupportContext(supportChain);

        structureDefinitions.stream()
                .filter(this::shouldGenerateSnapshot)
                .forEach(it -> {
                    try {
                        circularReferenceCheck(it, supportChain);
                    } catch (Exception e) {
                        log.error(String.format("Failed to generate snapshot for %s", it), e);
                    }
                });

        structureDefinitions.stream()
                .filter(this::shouldGenerateSnapshot)
                .forEach(it -> {
                    try {
                        supportChain.generateSnapshot(context, it, it.getUrl(), "https://fhir.nhs.uk/R4", it.getName());
                    } catch (Exception e) {
                        log.error(String.format("Failed to generate snapshot for %s", it), e);
                    }
                });
    }

    private boolean shouldGenerateSnapshot(StructureDefinition structureDefinition) {
        return !structureDefinition.hasSnapshot()
                && structureDefinition.getDerivation() == StructureDefinition.TypeDerivationRule.CONSTRAINT;
    }

    private StructureDefinition circularReferenceCheck(StructureDefinition structureDefinition,
            IValidationSupport supportChain) {
        if (structureDefinition.hasSnapshot()) {
            log.error(String.format("%s has snapshot!!", structureDefinition.getUrl()));
        }

        for (ElementDefinition element : structureDefinition.getDifferential().getElement()) {
            if ((element.getId().endsWith(".partOf") ||
                    element.getId().endsWith(".basedOn") ||
                    element.getId().endsWith(".replaces") ||
                    element.getId().contains("Condition.stage.assessment") ||
                    element.getId().contains("Observation.derivedFrom") ||
                    element.getId().contains("Observation.hasMember") ||
                    element.getId().contains("CareTeam.encounter") ||
                    element.getId().contains("CareTeam.reasonReference") ||
                    element.getId().contains("ServiceRequest.encounter") ||
                    element.getId().contains("ServiceRequest.reasonReference") ||
                    element.getId().contains("EpisodeOfCare.diagnosis.condition") ||
                    element.getId().contains("Encounter.diagnosis.condition") ||
                    element.getId().contains("Encounter.reasonReference") ||
                    element.getId().contains("Encounter.appointment")) && element.hasType()) {

                log.warn(String.format("%s has circular references (%s)", structureDefinition.getUrl(),
                        element.getId()));

                for (ElementDefinition.TypeRefComponent typeRef : element.getType()) {
                    if (typeRef.hasTargetProfile()) {
                        for (CanonicalType targetProfile : typeRef.getTargetProfile()) {
                            typeRef.setTargetProfile((List<CanonicalType>) getBase(targetProfile, supportChain));
                        }
                    }
                }
            }
        }
        return structureDefinition;
    }

    private CanonicalType getBase(CanonicalType profile, IValidationSupport supportChain) {
        StructureDefinition structureDefinition = (StructureDefinition) supportChain
                .fetchStructureDefinition(profile.toString());

        if (structureDefinition != null && structureDefinition.hasBaseDefinition()) {
            String baseProfile = structureDefinition.getBaseDefinition();
            CanonicalType canonicalBaseProfile = new CanonicalType(baseProfile);
            if (baseProfile.contains(".uk")) {
                canonicalBaseProfile = getBase(canonicalBaseProfile, supportChain);
            }
            return canonicalBaseProfile;
        }
        return null;
    }

    private InMemoryTerminologyServerValidationSupport terminologyValidationSupport(FhirContext fhirContext) {
        return new InMemoryTerminologyServerValidationSupport(fhirContext) {
            @Override
            public IValidationSupport.CodeValidationResult validateCodeInValueSet(
                    ValidationSupportContext theValidationSupportContext,
                    ConceptValidationOptions theOptions,
                    String theCodeSystem,
                    String theCode,
                    String theDisplay,
                    IBaseResource theValueSet) {
                String valueSetUrl = CommonCodeSystemsTerminologyService.getValueSetUrl(fhirContext, theValueSet);

                if ("https://fhir.nhs.uk/ValueSet/NHSDigital-MedicationRequest-Code".equals(valueSetUrl)
                        || "https://fhir.nhs.uk/ValueSet/NHSDigital-MedicationDispense-Code".equals(valueSetUrl)
                        || "https://fhir.hl7.org.uk/ValueSet/UKCore-MedicationCode".equals(valueSetUrl)) {
                    return new IValidationSupport.CodeValidationResult()
                            .setSeverity(IValidationSupport.IssueSeverity.WARNING)
                            .setMessage("Unable to validate medication codes");
                }

                return super.validateCodeInValueSet(
                        theValidationSupportContext,
                        theOptions,
                        theCodeSystem,
                        theCode,
                        theDisplay,
                        theValueSet);
            }
        };
    }

    private SimplifierPackage[] getPackages() {
        String manifestContent = ResourceUtils.getResourceContent(this.PROFILE_MANIFEST_FILE);
        return new Gson().fromJson(manifestContent, SimplifierPackage[].class);
    }
}
