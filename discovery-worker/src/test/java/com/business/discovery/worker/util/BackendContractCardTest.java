package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import com.business.discovery.worker.service.llm.PublicVariable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackendContractCardTest {

    private FileSpec svc(String path, PublicFunction... fns) {
        return FileSpec.builder().filePath(path).fileType("BACKEND").layer("SERVICE")
                .publicFunctions(List.of(fns)).build();
    }

    private FileSpec dto(String path, PublicVariable... vars) {
        return FileSpec.builder().filePath(path).fileType("BACKEND").layer("DTO")
                .publicVariables(List.of(vars)).build();
    }

    @Test
    void rendersReconcilerStyleMethodSignatureVerbatim() {
        // ContractReconciler stores the whole signature as a single param containing '('
        var card = BackendContractCard.build(List.of(
                svc("backend/src/main/java/com/x/service/MemberSubscriptionService.java",
                        PublicFunction.builder().name("createSubscription")
                                .parameters(List.of("createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto"))
                                .build())));

        assertThat(card.toPromptSection()).contains(
                "MemberSubscriptionService: methods: createSubscription(request: CreateSubscriptionRequest): MemberSubscriptionDto");
    }

    @Test
    void rendersEnrichmentStyleMethodFromNameParamsAndReturnType() {
        var card = BackendContractCard.build(List.of(
                svc("backend/src/main/java/com/x/repository/BookingRepository.java",
                        PublicFunction.builder().name("findByStatus")
                                .parameters(List.of("status: BookingStatus")).returnType("List<Booking>").build())));

        assertThat(card.toPromptSection()).contains(
                "BookingRepository: methods: findByStatus(status: BookingStatus): List<Booking>");
    }

    @Test
    void rendersDtoFieldsFromPublicVariables() {
        var card = BackendContractCard.build(List.of(
                dto("backend/src/main/java/com/x/dto/MembershipPlanDto.java",
                        new PublicVariable("id", "UUID", null),
                        new PublicVariable("durationMonths", "Integer", null))));

        assertThat(card.toPromptSection()).contains(
                "MembershipPlanDto: { id: UUID; durationMonths: Integer }");
    }

    @Test
    void skipsNonBackendAndEmptyInterfaces() {
        var card = BackendContractCard.build(List.of(
                FileSpec.builder().filePath("frontend/src/components/X.tsx").fileType("FRONTEND")
                        .publicFunctions(List.of(PublicFunction.builder().name("X").parameters(List.of("p: string")).build())).build(),
                FileSpec.builder().filePath("backend/src/main/java/com/x/util/Empty.java").fileType("BACKEND").build()));

        assertThat(card.isEmpty()).isTrue();
        assertThat(card.toPromptSection()).isEmpty();
    }

    @Test
    void headerAppearsAndClassesSortedDeterministically() {
        var card = BackendContractCard.build(List.of(
                dto("backend/src/main/java/com/x/dto/Zed.java", new PublicVariable("z", "int", null)),
                dto("backend/src/main/java/com/x/dto/Abc.java", new PublicVariable("a", "int", null))));

        String s = card.toPromptSection();
        assertThat(s).contains("RECONCILED BACKEND CONTRACTS (ground truth");
        assertThat(s).containsSubsequence("Abc:", "Zed:");   // TreeMap sorted
        assertThat(card.classCount()).isEqualTo(2);
    }
}
