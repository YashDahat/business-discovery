package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileContract;
import com.business.discovery.worker.service.llm.FileContract.Member;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowActionContractNormalizerTest {

    private static final String TABLE = "frontend/src/components/classes/ClassTable.tsx";

    private FileContract component(String module, Member... members) {
        return new FileContract(module, new ArrayList<>(List.of(members)), List.of());
    }

    private String typeOf(FileContract c, String prop) {
        return c.members().stream().filter(m -> m.name().equals(prop)).findFirst().orElseThrow().type();
    }

    @Test
    void rewritesIdCallbackToRowObject_andLeavesAlreadyObjectCallbackAsIs() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("classes", "FitnessClassDto[]"),
                new Member("onEdit", "(fitnessClass: FitnessClassDto) => void"),   // already object
                new Member("onDelete", "(classId: number) => void"))));            // id — the drift

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isEqualTo(1);
        assertThat(typeOf(contracts.get(0), "onDelete")).isEqualTo("(item: FitnessClassDto) => void");
        // both now take the same TYPE (FitnessClassDto) — the parent can no longer drift
        assertThat(typeOf(contracts.get(0), "onEdit")).isEqualTo("(fitnessClass: FitnessClassDto) => void");
    }

    @Test
    void rewritesUuidIdCallback_restaurantIdentityShape() {
        // farmaaish (9bdf03a2) shape: onEdit takes the object, onDelete takes a UUID id
        List<FileContract> contracts = new ArrayList<>(List.of(component(
                "frontend/src/components/admin/menu/MenuTable.tsx",
                new Member("menuItems", "MenuItemDto[]"),
                new Member("onEdit", "(item: MenuItemDto) => void"),
                new Member("onDelete", "(itemId: UUID) => void"))));

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isEqualTo(1);
        assertThat(typeOf(contracts.get(0), "onDelete")).isEqualTo("(item: MenuItemDto) => void");
    }

    @Test
    void anchorsOnItemsPropWhenMultipleDtoArraysPresent() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("items", "OrderDto[]"),
                new Member("relatedProducts", "ProductDto[]"),
                new Member("onView", "(id: number) => void"))));

        RowActionContractNormalizer.normalize(contracts);

        assertThat(typeOf(contracts.get(0), "onView")).isEqualTo("(item: OrderDto) => void");
    }

    @Test
    void leavesTableControlsUntouched_denyListAndNonIdParams() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("rows", "TrainerDto[]"),
                new Member("onSort", "(column: string) => void"),
                new Member("onPageChange", "(page: number) => void"),
                new Member("onSelectAll", "(checked: boolean) => void"),
                new Member("onSelect", "(value: string) => void"))));   // allowlisted name, but value not id-like

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();
        assertThat(typeOf(contracts.get(0), "onSort")).isEqualTo("(column: string) => void");
        assertThat(typeOf(contracts.get(0), "onPageChange")).isEqualTo("(page: number) => void");
        assertThat(typeOf(contracts.get(0), "onSelectAll")).isEqualTo("(checked: boolean) => void");
        assertThat(typeOf(contracts.get(0), "onSelect")).isEqualTo("(value: string) => void");
    }

    @Test
    void selectAllIsNotAdmittedBySelectPrefix() {
        // exact-match guard: onSelectAll must not slip through onSelect
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("rows", "UserDto[]"),
                new Member("onSelectAll", "(id: number) => void"))));   // even with an id param

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();
    }

    @Test
    void skipsMultiParamCallbacks() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("items", "CartItemDto[]"),
                new Member("onSelect", "(id: number, extra: string) => void"))));

        RowActionContractNormalizer.normalize(contracts);

        assertThat(typeOf(contracts.get(0), "onSelect")).isEqualTo("(id: number, extra: string) => void");
    }

    @Test
    void preservesNonVoidReturnType() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("items", "BookingDto[]"),
                new Member("onDelete", "(bookingId: string) => Promise<void>"))));

        RowActionContractNormalizer.normalize(contracts);

        assertThat(typeOf(contracts.get(0), "onDelete")).isEqualTo("(item: BookingDto) => Promise<void>");
    }

    @Test
    void skipsComponentWithoutAnUnambiguousRowType() {
        // a form/dialog with no list prop — nothing to anchor on
        List<FileContract> contracts = new ArrayList<>(List.of(component(
                "frontend/src/components/classes/DeleteClassDialog.tsx",
                new Member("open", "boolean"),
                new Member("onDelete", "(id: number) => void"))));

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();
        assertThat(typeOf(contracts.get(0), "onDelete")).isEqualTo("(id: number) => void");
    }

    @Test
    void skipsWhenTwoDistinctDtoArraysAndNoItemsProp() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("classes", "FitnessClassDto[]"),
                new Member("trainers", "TrainerDto[]"),
                new Member("onDelete", "(id: number) => void"))));

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();   // ambiguous row type → skip
    }

    @Test
    void ignoresNonComponentContracts() {
        List<FileContract> contracts = new ArrayList<>(List.of(new FileContract(
                "backend/src/main/java/com/x/dto/OrderDto.java",
                List.of(new Member("onDelete", "(id: number) => void")), List.of())));

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();
    }

    @Test
    void isIdempotentAcrossReruns() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("classes", "FitnessClassDto[]"),
                new Member("onDelete", "(classId: number) => void"))));

        assertThat(RowActionContractNormalizer.normalize(contracts)).isEqualTo(1);
        String afterFirst = typeOf(contracts.get(0), "onDelete");
        assertThat(RowActionContractNormalizer.normalize(contracts)).isZero();   // second run: no-op
        assertThat(typeOf(contracts.get(0), "onDelete")).isEqualTo(afterFirst);
    }

    @Test
    void ignoresDateArraysAndOtherNonDtoRowTypes() {
        List<FileContract> contracts = new ArrayList<>(List.of(component(TABLE,
                new Member("dates", "Date[]"),
                new Member("onSelect", "(id: number) => void"))));

        int changed = RowActionContractNormalizer.normalize(contracts);

        assertThat(changed).isZero();   // Date[] is not a domain row type
    }
}
