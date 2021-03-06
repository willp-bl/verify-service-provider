package unit.uk.gov.ida.verifyserviceprovider.services;

import com.google.common.collect.ImmutableList;
import net.shibboleth.utilities.java.support.component.ComponentInitializationException;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.crypto.KeySupport;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.impl.ExplicitKeySignatureTrustEngine;
import uk.gov.ida.saml.core.IdaSamlBootstrap;
import uk.gov.ida.saml.core.domain.SamlStatusCode;
import uk.gov.ida.saml.core.extensions.IdaAuthnContext;
import uk.gov.ida.saml.core.test.PrivateKeyStoreFactory;
import uk.gov.ida.saml.core.test.TestCredentialFactory;
import uk.gov.ida.saml.core.test.TestEntityIds;
import uk.gov.ida.saml.core.test.builders.AssertionBuilder;
import uk.gov.ida.saml.core.test.builders.ResponseBuilder;
import uk.gov.ida.saml.core.test.builders.SimpleStringAttributeBuilder;
import uk.gov.ida.saml.core.validation.SamlTransformationErrorException;
import uk.gov.ida.saml.metadata.factories.MetadataSignatureTrustEngineFactory;
import uk.gov.ida.saml.security.SamlAssertionsSignatureValidator;
import uk.gov.ida.saml.serializers.XmlObjectToBase64EncodedStringTransformer;
import uk.gov.ida.verifyserviceprovider.dto.LevelOfAssurance;
import uk.gov.ida.verifyserviceprovider.dto.TranslatedResponseBody;
import uk.gov.ida.verifyserviceprovider.exceptions.SamlResponseValidationException;
import uk.gov.ida.verifyserviceprovider.factories.saml.ResponseFactory;
import uk.gov.ida.verifyserviceprovider.services.AssertionTranslator;
import uk.gov.ida.verifyserviceprovider.services.ResponseService;
import uk.gov.ida.verifyserviceprovider.utils.DateTimeComparator;
import uk.gov.ida.verifyserviceprovider.validators.AssertionValidator;
import uk.gov.ida.verifyserviceprovider.validators.AudienceRestrictionValidator;
import uk.gov.ida.verifyserviceprovider.validators.ConditionsValidator;
import uk.gov.ida.verifyserviceprovider.validators.InstantValidator;
import uk.gov.ida.verifyserviceprovider.validators.SubjectValidator;
import uk.gov.ida.verifyserviceprovider.validators.TimeRestrictionValidator;

import java.security.KeyException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.List;

import static common.uk.gov.ida.verifyserviceprovider.utils.SamlResponseHelper.createVerifiedAttribute;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_PUBLIC_CERT;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PRIVATE_ENCRYPTION_KEY;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PRIVATE_SIGNING_KEY;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PUBLIC_ENCRYPTION_CERT;
import static uk.gov.ida.saml.core.test.TestCertificateStrings.TEST_RP_PUBLIC_SIGNING_CERT;
import static uk.gov.ida.saml.core.test.builders.AssertionBuilder.anAssertion;
import static uk.gov.ida.saml.core.test.builders.AttributeStatementBuilder.anAttributeStatement;
import static uk.gov.ida.saml.core.test.builders.AudienceRestrictionBuilder.anAudienceRestriction;
import static uk.gov.ida.saml.core.test.builders.AuthnContextBuilder.anAuthnContext;
import static uk.gov.ida.saml.core.test.builders.AuthnContextClassRefBuilder.anAuthnContextClassRef;
import static uk.gov.ida.saml.core.test.builders.AuthnStatementBuilder.anAuthnStatement;
import static uk.gov.ida.saml.core.test.builders.ConditionsBuilder.aConditions;
import static uk.gov.ida.saml.core.test.builders.NameIdBuilder.aNameId;
import static uk.gov.ida.saml.core.test.builders.ResponseBuilder.aResponse;
import static uk.gov.ida.saml.core.test.builders.StatusBuilder.aStatus;
import static uk.gov.ida.saml.core.test.builders.StatusCodeBuilder.aStatusCode;
import static uk.gov.ida.saml.core.test.builders.SubjectBuilder.aSubject;
import static uk.gov.ida.saml.core.test.builders.metadata.EntityDescriptorBuilder.anEntityDescriptor;
import static uk.gov.ida.saml.core.test.builders.metadata.KeyDescriptorBuilder.aKeyDescriptor;
import static uk.gov.ida.saml.core.test.builders.metadata.SPSSODescriptorBuilder.anSpServiceDescriptor;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.ACCOUNT_CREATION;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.AUTHENTICATION_FAILED;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.CANCELLATION;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.NO_MATCH;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.REQUEST_ERROR;
import static uk.gov.ida.verifyserviceprovider.dto.Scenario.SUCCESS_MATCH;

public class ResponseServiceTest {

    private static final String VERIFY_SERVICE_PROVIDER_ENTITY_ID = "some-entity-id";

    private ResponseService responseService;

    private XmlObjectToBase64EncodedStringTransformer<XMLObject> responseToBase64StringTransformer = new XmlObjectToBase64EncodedStringTransformer<>();

    private MetadataResolver hubMetadataResolver;

    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    private TestCredentialFactory encryptionCredentialFactory;
    private Credential testRpSigningCredential;

    @Before
    public void setUp() throws ComponentInitializationException, KeyException {
        // Note: the private key and the encrypting credential need to be from the same keypair
        PrivateKey privateKey = new PrivateKeyStoreFactory().create(TestEntityIds.TEST_RP).getEncryptionPrivateKeys().get(0);
        KeyPair keyPair = new KeyPair(KeySupport.derivePublicKey(privateKey), privateKey);
        List<KeyPair>  keyPairs = asList(keyPair, keyPair);
        encryptionCredentialFactory = new TestCredentialFactory(TEST_RP_PUBLIC_ENCRYPTION_CERT, TEST_RP_PRIVATE_ENCRYPTION_KEY);
        testRpSigningCredential = new TestCredentialFactory(TEST_RP_PUBLIC_SIGNING_CERT, TEST_RP_PRIVATE_SIGNING_KEY).getSigningCredential();

        hubMetadataResolver = mock(MetadataResolver.class);

        ResponseFactory responseFactory = new ResponseFactory(keyPairs);
        DateTimeComparator dateTimeComparator = new DateTimeComparator(Duration.standardSeconds(5));
        TimeRestrictionValidator timeRestrictionValidator = new TimeRestrictionValidator(dateTimeComparator);

        SamlAssertionsSignatureValidator samlAssertionsSignatureValidator = mock(SamlAssertionsSignatureValidator.class);
        InstantValidator instantValidator = new InstantValidator(dateTimeComparator);
        SubjectValidator subjectValidator = new SubjectValidator(timeRestrictionValidator);
        ConditionsValidator conditionsValidator = new ConditionsValidator(timeRestrictionValidator, new AudienceRestrictionValidator());
        AssertionValidator assertionValidator = new AssertionValidator(instantValidator, subjectValidator, conditionsValidator);
        AssertionTranslator assertionTranslator = new AssertionTranslator(samlAssertionsSignatureValidator, assertionValidator);

        ExplicitKeySignatureTrustEngine signatureTrustEngine = new MetadataSignatureTrustEngineFactory().createSignatureTrustEngine(hubMetadataResolver);

        responseService = responseFactory.createResponseService(
            signatureTrustEngine,
            assertionTranslator,
            dateTimeComparator
        );
    }

    @Before
    public void bootStrapOpenSaml() {
        IdaSamlBootstrap.bootstrap();
    }

    @Test
    public void shouldHandleSuccessMatchSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status successStatus = aStatus().
            withStatusCode(aStatusCode().withValue(StatusCode.SUCCESS).build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(successStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result).isEqualTo(new TranslatedResponseBody(
            SUCCESS_MATCH,
            "some-pid",
            LevelOfAssurance.LEVEL_2,
            null
        ));
    }

    @Test
    public void shouldHandleAccountCreationSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status successStatus = aStatus().
            withStatusCode(aStatusCode().withValue(StatusCode.SUCCESS).build())
            .build();
        Response response = signResponse(createAttributeResponseBuilder(successStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result.getScenario()).isEqualTo(ACCOUNT_CREATION);
        assertThat(result.getAttributes()).isNotNull();
    }

    @Test
    public void shouldHandleNoMatchSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue(StatusCode.RESPONDER)
                    .withSubStatusCode(aStatusCode().withValue(SamlStatusCode.NO_MATCH).build())
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result.getScenario()).isEqualTo(NO_MATCH);
    }

    @Test
    public void shouldHandleRequestErrorSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue(StatusCode.RESPONDER)
                    .withSubStatusCode(aStatusCode().withValue(StatusCode.REQUESTER).build())
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result.getScenario()).isEqualTo(REQUEST_ERROR);
    }

    @Test
    public void shouldHandleNoAuthnContextSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue(StatusCode.RESPONDER)
                    .withSubStatusCode(aStatusCode().withValue(StatusCode.NO_AUTHN_CONTEXT).build())
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result.getScenario()).isEqualTo(CANCELLATION);
    }

    @Test
    public void shouldHandleAuthenticationFailedSaml() throws Exception {
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue(StatusCode.RESPONDER)
                    .withSubStatusCode(aStatusCode().withValue(StatusCode.AUTHN_FAILED).build())
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        TranslatedResponseBody result = responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );

        assertThat(result.getScenario()).isEqualTo(AUTHENTICATION_FAILED);
    }

    @Test
    public void shouldFailWhenUnrecognizedStatus() throws Exception {
        expectedException.expect(SamlResponseValidationException.class);
        expectedException.expectMessage("Unknown SAML status: UNKNOWN");

        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue("UNKNOWN")
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailWhenUnrecognizedSubStatus() throws Exception {
        expectedException.expect(SamlResponseValidationException.class);
        expectedException.expectMessage("Unknown SAML sub-status: UNKNOWN");

        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status noMatchStatus = aStatus().
            withStatusCode(
                aStatusCode()
                    .withValue(StatusCode.RESPONDER)
                    .withSubStatusCode(aStatusCode().withValue("UNKNOWN").build())
                    .build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(noMatchStatus), testRpSigningCredential);

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailValidationWhenMetadataDoesNotContainCorrectCertificate() throws Exception {
        expectedException.expect(SamlTransformationErrorException.class);
        expectedException.expectMessage("SAML Validation Specification: Signature was not valid.");

        Status successStatus = aStatus().
            withStatusCode(aStatusCode().withValue(StatusCode.SUCCESS).build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(successStatus), testRpSigningCredential);
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_PUBLIC_CERT);

        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailValidationWhenResponseIsNotSigned() throws Exception {
        expectedException.expect(SamlTransformationErrorException.class);
        expectedException.expectMessage("SAML Validation Specification: Message signature is not signed");

        Status successStatus = aStatus().
            withStatusCode(aStatusCode().withValue(StatusCode.SUCCESS).build())
            .build();
        Response response = createNoAttributeResponseBuilder(successStatus).withoutSigning().build();
        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);

        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailWhenInResponseToDoesNotMatchRequestId() throws Exception {
        expectedException.expect(SamlResponseValidationException.class);
        expectedException.expectMessage("Expected InResponseTo to be some-incorrect-request-id, but was default-request-id");

        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        Status successStatus = aStatus().
            withStatusCode(aStatusCode().withValue(StatusCode.SUCCESS).build())
            .build();
        Response response = signResponse(createNoAttributeResponseBuilder(successStatus), testRpSigningCredential);

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            "some-incorrect-request-id",
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailWhenIssueInstantIsTooOld() throws Exception {
        expectedException.expect(SamlResponseValidationException.class);
        expectedException.expectMessage("Response IssueInstant is too far in the past ");

        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        ResponseBuilder responseBuilder = aResponse().withIssueInstant(DateTime.now().minusMinutes(10));
        Response response = signResponse(responseBuilder, testRpSigningCredential);

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    @Test
    public void shouldFailWhenIssueInstantIsInTheFuture() throws Exception {
        expectedException.expect(SamlResponseValidationException.class);
        expectedException.expectMessage("Response IssueInstant is in the future ");

        EntityDescriptor entityDescriptor = createEntityDescriptorWithSigningCertificate(TEST_RP_PUBLIC_SIGNING_CERT);
        when(hubMetadataResolver.resolve(any())).thenReturn(ImmutableList.of(entityDescriptor));

        ResponseBuilder responseBuilder = aResponse().withIssueInstant(DateTime.now().plusMinutes(1));
        Response response = signResponse(responseBuilder, testRpSigningCredential);

        responseService.convertTranslatedResponseBody(
            responseToBase64StringTransformer.apply(response),
            response.getInResponseTo(),
            LevelOfAssurance.LEVEL_2,
            VERIFY_SERVICE_PROVIDER_ENTITY_ID
        );
    }

    private EntityDescriptor createEntityDescriptorWithSigningCertificate(String signingCert) throws MarshallingException, SignatureException {
        return anEntityDescriptor()
            .addSpServiceDescriptor(anSpServiceDescriptor()
                .withoutDefaultSigningKey()
                .addKeyDescriptor(aKeyDescriptor().withX509ForSigning(signingCert).build())
                .build()
            )
            .build();
    }

    private Response signResponse(ResponseBuilder responseBuilder, Credential signingCredential) throws MarshallingException, SignatureException {
        return responseBuilder
            .withSigningCredential(signingCredential).build();
    }

    private ResponseBuilder createNoAttributeResponseBuilder(Status samlStatus) {
        return aResponse()
            .withStatus(samlStatus)
            .withNoDefaultAssertion()
            .addEncryptedAssertion(aDefaultAssertion()
                .buildWithEncrypterCredential(encryptionCredentialFactory.getEncryptingCredential())
            );
    }

    private ResponseBuilder createAttributeResponseBuilder(Status samlStatus) {
        return aResponse()
            .withStatus(samlStatus)
            .withNoDefaultAssertion()
            .addEncryptedAssertion(aDefaultAssertion()
                .addAttributeStatement(
                    anAttributeStatement()
                        .addAttribute(new SimpleStringAttributeBuilder()
                            .withName("FIRST_NAME")
                            .withSimpleStringValue("Bob")
                            .build())
                        .addAttribute(createVerifiedAttribute("FIRST_NAME_VERIFIED", true))
                        .build())
                .buildWithEncrypterCredential(encryptionCredentialFactory.getEncryptingCredential())
            );
    }

    private AssertionBuilder aDefaultAssertion() {
        return
            anAssertion()
                .withSubject(aSubject()
                    .withNameId(aNameId().withValue("some-pid").build())
                    .build())
                .withConditions(aConditions()
                    .withoutDefaultAudienceRestriction()
                    .addAudienceRestriction(anAudienceRestriction()
                        .withAudienceId(VERIFY_SERVICE_PROVIDER_ENTITY_ID)
                        .build())
                    .build())
                .addAuthnStatement(anAuthnStatement()
                    .withAuthnContext(anAuthnContext()
                        .withAuthnContextClassRef(anAuthnContextClassRef()
                            .withAuthnContextClasRefValue(IdaAuthnContext.LEVEL_2_AUTHN_CTX)
                            .build())
                        .build())
                    .build());
    }

}