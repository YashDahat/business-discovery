package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures are drawn from the Farmaaish run (worker-620bb653) error surface:
 * useEvents.createEvent (×2), AuthUser.id (×2), CheckoutStep type-as-value (×5),
 * undefined vs null (×3). Each test targets one error class.
 */
class FrontendContractCardTest {

    @TempDir
    Path workspace;

    // ── Hook return type extraction ───────────────────────────────────────────

    @Test
    void extractsHookReturnTypeFromFunctionForm() throws Exception {
        write("frontend/src/hooks/useEvents.ts", """
                import { useQuery, useMutation } from '@tanstack/react-query';
                import type { EventDto, EventCreate } from '@/types/event';

                export function useEvents(): { events: EventDto[]; isLoading: boolean; create: UseMutationResult<EventDto, Error, EventCreate>; update: UseMutationResult<EventDto, Error, EventDto> } {
                  const { data: events = [], isLoading } = useQuery({ queryKey: ['events'], queryFn: getAllEvents });
                  const create = useMutation({ mutationFn: createEvent });
                  const update = useMutation({ mutationFn: updateEvent });
                  return { events, isLoading, create, update };
                }
                """);

        FrontendContractCard card = FrontendContractCard.build(workspace);
        String section = card.toPromptSection();

        // The real return shape must appear — consumers must use create.mutateAsync, not createEvent()
        assertThat(section).contains("useEvents()");
        assertThat(section).contains("create: UseMutationResult");
        assertThat(section).contains("update: UseMutationResult");
        // The invented names that caused ×2 errors must not be present
        assertThat(section).doesNotContain("createEvent");
        assertThat(section).doesNotContain("updateEvent");
        assertThat(card.moduleCount()).isEqualTo(1);
    }

    @Test
    void extractsHookReturnTypeFromArrowConstForm() throws Exception {
        write("frontend/src/hooks/useOrders.ts", """
                export const useOrders = (): { orders: OrderDto[]; isLoading: boolean; refetch: () => void } => {
                  const { data: orders = [], isLoading, refetch } = useQuery({ queryKey: ['orders'], queryFn: getAllOrders });
                  return { orders, isLoading, refetch };
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("useOrders()");
        assertThat(section).contains("orders: OrderDto[]");
        assertThat(section).contains("isLoading: boolean");
    }

    // ── Interface / type field extraction ────────────────────────────────────

    @Test
    void extractsInterfaceFieldsWithCorrectNames() throws Exception {
        // AuthUser.id vs AuthUser.userId — the ×2 error from the motivating run
        write("frontend/src/types/local/auth.ts", """
                export interface AuthUser {
                  userId: string;
                  email: string;
                  roles: string[];
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        // The real field name must appear — consumers must not access .id
        assertThat(section).contains("userId: string");
        assertThat(section).contains("email: string");
        assertThat(section).contains("roles: string[]");
    }

    @Test
    void tracksNullabilityOnInterfaceFields() throws Exception {
        write("frontend/src/types/local/reservation.ts", """
                export interface ReservationDto {
                  id: string;
                  guestName: string;
                  specialRequests: string | null;
                  partySize: number;
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("specialRequests: string | null");
        assertThat(section).contains("null-guard required");
        // Non-nullable fields must not carry the warning
        assertThat(section).contains("id: string");
    }

    @Test
    void tracksOptionalVsNullableDistinction() throws Exception {
        // undefined (optional ?) vs null (| null) — the ×3 error class
        write("frontend/src/types/local/profile.ts", """
                export interface UserProfile {
                  name: string;
                  bio?: string;
                  avatarUrl: string | null;
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("bio?: string");          // optional — no null-guard note
        assertThat(section).contains("avatarUrl: string | null");
        assertThat(section).contains("null-guard required");
        // bio is optional (undefined), not nullable — must not carry null-guard note
        assertThat(section).doesNotContain("bio?: string  /* null-guard required */");
    }

    @Test
    void extractsTypeAliasFields() throws Exception {
        write("frontend/src/types/local/checkout.ts", """
                export type CheckoutStep = { id: string; label: string; validate?: () => boolean | string }
                export type CheckoutController = { steps: CheckoutStep[]; current?: CheckoutStep; index: number; isFirst: boolean; isLast: boolean; next(): boolean; back(): void }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("CheckoutStep");
        assertThat(section).contains("CheckoutController");
    }

    // ── Type-only file ⚠ annotation ──────────────────────────────────────────

    @Test
    void emitsTypeNotValueWarningForTypeOnlyFile() throws Exception {
        // CheckoutStep ×5: consumers tried CheckoutStep.PAYMENT as if it were an enum
        write("frontend/src/types/local/checkout.ts", """
                export interface CheckoutStep {
                  id: string;
                  label: string;
                }
                export interface CheckoutController {
                  steps: CheckoutStep[];
                  next(): boolean;
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("⚠ These are TYPES");
        assertThat(section).contains("no enum-style value access");
    }

    @Test
    void doesNotEmitTypeWarningWhenFileAlsoExportsValues() throws Exception {
        write("frontend/src/hooks/useCheckout.ts", """
                export interface CheckoutStep { id: string; label: string }
                export function useCheckout(steps: CheckoutStep[]): { current: CheckoutStep; next(): boolean } {
                  return { current: steps[0], next: () => true };
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        // File exports both a type AND a function — no warning
        assertThat(section).doesNotContain("⚠ These are TYPES");
    }

    // ── Context extraction ────────────────────────────────────────────────────

    @Test
    void extractsContextHookSignature() throws Exception {
        write("frontend/src/context/AuthContext.tsx",
                "// GENERATED foundation scaffold\n" + """
                import React, { createContext, useContext } from 'react';
                export interface AuthUser { userId: string; email: string; roles: string[] }
                export function useAuth(): { user: AuthUser | null; isAuthenticated: boolean; login(email: string, password: string): Promise<void>; logout(): void } {
                  return useContext(AuthContext);
                }
                """);

        FrontendContractCard card = FrontendContractCard.build(workspace);
        String section = card.toPromptSection();

        assertThat(section).contains("useAuth()");
        assertThat(section).contains("isAuthenticated: boolean");
        assertThat(card.moduleCount()).isGreaterThanOrEqualTo(1);
    }

    // ── Component props extraction ────────────────────────────────────────────

    @Test
    void extractsNamedPropsInterfaceFromComponent() throws Exception {
        write("frontend/src/components/MenuCard.tsx", """
                interface MenuCardProps {
                  item: MenuItemDto;
                  onAddToCart: (item: CartItem) => void;
                  className?: string;
                }
                export default function MenuCard({ item, onAddToCart, className }: MenuCardProps) {
                  return <div className={className}>{item.name}</div>;
                }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).contains("MenuCardProps");
        assertThat(section).contains("item: MenuItemDto");
        assertThat(section).contains("onAddToCart");
        assertThat(section).contains("className?: string");
    }

    @Test
    void skipsUiComponentsAlreadyCoveredByUiInventory() throws Exception {
        write("frontend/src/components/ui/button.tsx", """
                export interface ButtonProps { variant?: string; children: React.ReactNode }
                export function Button({ variant, children }: ButtonProps) { return <button>{children}</button>; }
                """);

        FrontendContractCard card = FrontendContractCard.build(workspace);

        assertThat(card.isEmpty()).isTrue();
    }

    // ── Derived files are skipped ─────────────────────────────────────────────

    @Test
    void doesNotExtractDerivedTypesAlreadyCoveredByApiContractCard() throws Exception {
        // Derived types in frontend/src/types/ (not local/) belong to ApiContractCard
        write("frontend/src/types/order.ts",
                "// GENERATED from the backend API contract\n"
                + "export interface OrderDto { id: string; totalAmount: number | null; }\n");
        // A local type in the same run
        write("frontend/src/types/local/cart.ts",
                "export interface CartSummary { itemCount: number; total: number; }\n");

        FrontendContractCard card = FrontendContractCard.build(workspace);
        String section = card.toPromptSection();

        assertThat(section).doesNotContain("OrderDto");          // derived — ApiContractCard's job
        assertThat(section).contains("CartSummary");             // local — our job
    }

    @Test
    void doesNotExtractDerivedServices() throws Exception {
        write("frontend/src/services/orderService.ts",
                "// GENERATED from the backend API contract\n"
                + "export const getAllOrders = async (): Promise<OrderDto[]> => {};\n");

        FrontendContractCard card = FrontendContractCard.build(workspace);

        assertThat(card.isEmpty()).isTrue();
    }

    // ── Incremental registration ──────────────────────────────────────────────

    @Test
    void cardIsEmptyBeforeAnyRegistration() {
        FrontendContractCard card = new FrontendContractCard();

        assertThat(card.isEmpty()).isTrue();
        assertThat(card.moduleCount()).isZero();
        assertThat(card.toPromptSection()).isEmpty();
    }

    @Test
    void growsIncrementallyWithEachRegisteredLayer() throws Exception {
        Path hookFile = writeAndReturn("frontend/src/hooks/useMenu.ts", """
                export function useMenu(): { items: MenuItemDto[]; isLoading: boolean } {
                  return { items: [], isLoading: false };
                }
                """);
        Path typeFile = writeAndReturn("frontend/src/types/local/menu.ts", """
                export interface MenuItemDto { id: string; name: string; price: number }
                """);

        FrontendContractCard card = new FrontendContractCard();
        assertThat(card.isEmpty()).isTrue();

        card.register(typeFile, Files.readString(typeFile));
        assertThat(card.moduleCount()).isEqualTo(1);
        assertThat(card.toPromptSection()).contains("MenuItemDto");
        assertThat(card.toPromptSection()).doesNotContain("useMenu");

        card.register(hookFile, Files.readString(hookFile));
        assertThat(card.moduleCount()).isEqualTo(2);
        assertThat(card.toPromptSection()).contains("useMenu");
        assertThat(card.toPromptSection()).contains("MenuItemDto");
    }

    @Test
    void registerIsNoOpForPageAndComponentFiles() throws Exception {
        Path pageFile = writeAndReturn("frontend/src/pages/MenuPage.tsx", """
                export default function MenuPage() { return <div>Menu</div>; }
                """);
        Path componentFile = writeAndReturn("frontend/src/components/MenuCard.tsx", """
                export default function MenuCard() { return <div/>; }
                """);

        FrontendContractCard card = new FrontendContractCard();
        card.register(pageFile, Files.readString(pageFile));
        card.register(componentFile, Files.readString(componentFile));

        assertThat(card.isEmpty()).isTrue();
    }

    // ── buildImmutableOnly ────────────────────────────────────────────────────

    @Test
    void buildImmutableOnlyPicksUpCartSpineFiles() throws Exception {
        write("frontend/src/cart/CartContext.tsx",
                "// GENERATED cart spine\n" + """
                export function useCart(): { cartItems: CartItem[]; addItem(item: CartItem): void; cartCount: number } {
                  return useContext(CartCtx);
                }
                """);
        // A regular generated hook — must NOT appear in the immutable card
        write("frontend/src/hooks/useOrders.ts", """
                export function useOrders(): { orders: OrderDto[] } { return { orders: [] }; }
                """);

        FrontendContractCard card = FrontendContractCard.buildImmutableOnly(workspace);
        String section = card.toPromptSection();

        assertThat(section).contains("useCart()");
        assertThat(section).contains("cartItems: CartItem[]");
        assertThat(section).doesNotContain("useOrders");         // mutable — excluded
    }

    @Test
    void buildImmutableOnlyPicksUpFoundationScaffoldFiles() throws Exception {
        write("frontend/src/context/AuthContext.tsx",
                "// GENERATED foundation scaffold\n" + """
                export function useAuth(): { isAuthenticated: boolean; user: AuthUser | null } {
                  return useContext(AuthCtx);
                }
                """);
        // A planner-generated context — must NOT appear
        write("frontend/src/context/BookingContext.tsx", """
                export function useBooking(): { bookings: BookingDto[] } { return { bookings: [] }; }
                """);

        FrontendContractCard card = FrontendContractCard.buildImmutableOnly(workspace);
        String section = card.toPromptSection();

        assertThat(section).contains("useAuth()");
        assertThat(section).doesNotContain("useBooking");
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Test
    void promptSectionIsRunConstantForCaching() throws Exception {
        write("frontend/src/hooks/useTrainers.ts", """
                export function useTrainers(): { trainers: TrainerDto[]; isLoading: boolean } {
                  return { trainers: [], isLoading: false };
                }
                """);

        assertThat(FrontendContractCard.build(workspace).toPromptSection())
                .isEqualTo(FrontendContractCard.build(workspace).toPromptSection());
    }

    @Test
    void emptyWorkspaceProducesEmptyCard() {
        FrontendContractCard card = FrontendContractCard.build(workspace.resolve("nonexistent"));

        assertThat(card.isEmpty()).isTrue();
        assertThat(card.toPromptSection()).isEmpty();
    }

    @Test
    void sectionContainsHeaderAndFooter() throws Exception {
        write("frontend/src/hooks/useFoo.ts", """
                export function useFoo(): { value: string } { return { value: '' }; }
                """);

        String section = FrontendContractCard.build(workspace).toPromptSection();

        assertThat(section).startsWith("── FRONTEND MODULE CONTRACTS");
        assertThat(section).endsWith("──────────────────────────────────────────────────────────────────────────────");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void write(String rel, String content) throws Exception {
        Path p = workspace.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Path writeAndReturn(String rel, String content) throws Exception {
        write(rel, content);
        return workspace.resolve(rel);
    }
}
