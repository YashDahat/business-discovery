package com.business.discovery.worker.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the canonical, business-agnostic commerce framework under {@code frontend/src/cart/} — the
 * universal product/service → cart → order machinery every generated storefront needs, whatever it
 * sells (restaurant food/drinks, gym schemes/classes, wholesale products/electronics). Making it
 * deterministic removes the cross-feature seam the LLM drops (drifting cart method names, a cart the
 * checkout can never fill) and gives every site the same correct, configurable core.
 *
 * <p><b>Headless by design.</b> This framework ships state + logic only — NO rendered UI. The LLM
 * builds every cart-facing element (the add control, quantity control, cart view) with full creative
 * freedom by calling {@code useCart()} / {@code useCheckout()}. That keeps the correctness guarantees
 * (fixed API, correct pricing/merge/persistence) while leaving all presentation to the generator.
 *
 * <p>The module is split by SOLID responsibility so it extends by configuration, never by editing:
 * <ul>
 *   <li><b>SRP</b> — one file per concern: types, pricing engine, persistence, config, cart state,
 *       checkout flow.</li>
 *   <li><b>OCP</b> — add tax/delivery/discount via {@code pricingRules} + factories, add a checkout
 *       step via the {@code useCheckout(steps)} data list, add an item kind via generic
 *       {@code CartItem} — none touch the engine.</li>
 *   <li><b>LSP</b> — any {@code PricingRule}, {@code CartStorage}, {@code CheckoutStep} is
 *       substitutable through its interface.</li>
 *   <li><b>ISP</b> — {@code useCart} (state), {@code useCheckout} (flow) and {@code computeTotals}
 *       (pricing) are separate; a consumer depends only on what it uses.</li>
 *   <li><b>DIP</b> — the provider depends on the {@code CartStorage} and {@code PricingRule}
 *       abstractions, injected from {@code cartConfig.ts}, not on localStorage or a fixed tax rule.</li>
 * </ul>
 *
 * <p>Every mechanism file carries {@link #FENCE_MARKER} on its first line so FrontendGeneratorNode
 * refuses to regenerate it, and App.tsx provider discovery mounts {@code CartProvider} automatically.
 * {@code cartConfig.ts} is the one business edit point and is written only when absent (customization
 * survives retries).
 */
@Slf4j
public final class CartSpineScaffold {

    /** First-line marker FrontendGeneratorNode.isFenced() keys on to refuse LLM regeneration. */
    public static final String FENCE_MARKER = "GENERATED cart spine";

    private CartSpineScaffold() {}

    /** Writes the cart module under {@code frontendSrc} (the frontend/src directory). */
    public static void write(Path frontendSrc) throws IOException {
        Path cart = frontendSrc.resolve("cart");
        writeFile(cart.resolve("types.ts"), TYPES);
        writeFile(cart.resolve("pricing.ts"), PRICING);
        writeFile(cart.resolve("storage.ts"), STORAGE);
        writeFile(cart.resolve("CartContext.tsx"), CART_CONTEXT);
        writeFile(cart.resolve("useCheckout.ts"), USE_CHECKOUT);
        writeFile(cart.resolve("index.ts"), INDEX);
        // Config is the single business edit point — write it only if absent so tuning survives retries.
        writeIfAbsent(cart.resolve("cartConfig.ts"), CART_CONFIG);
        log.info("[CartSpineScaffold] Wrote headless cart framework under {}", cart);
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void writeIfAbsent(Path path, String content) throws IOException {
        if (Files.exists(path)) return;
        writeFile(path, content);
    }

    // ── templates ─────────────────────────────────────────────────────────

    private static final String TYPES = """
            // GENERATED cart spine — canonical commerce contracts, do not edit.

            /**
             * A single line in the cart. Generic across verticals: a dish, a drink, a gym scheme, a
             * ticket, or a wholesale product all map to this shape. `id` is the product/service id;
             * `variantKey` distinguishes variants of the same id (size, add-ons, time-slot); `metadata`
             * carries any domain-specific data and never affects pricing or merging.
             */
            export interface CartItem {
              id: string | number;
              name: string;
              unitPrice: number;
              quantity: number;
              imageUrl?: string | null;
              variantKey?: string;
              metadata?: Record<string, unknown>;
            }

            export interface PricingContext {
              items: CartItem[];
              subtotal: number;
              runningTotal: number;
            }

            /** A single, composable price adjustment. Returns a signed amount: + for fees/tax, - for discounts. */
            export interface PricingRule {
              id: string;
              label: string;
              apply: (ctx: PricingContext) => number;
            }

            export interface AdjustmentLine {
              id: string;
              label: string;
              amount: number;
            }

            export interface CartTotals {
              subtotal: number;
              adjustments: AdjustmentLine[];
              total: number;
            }

            /** Persistence strategy — swap localStorage for a session or backend-synced implementation. */
            export interface CartStorage {
              load: () => CartItem[];
              save: (items: CartItem[]) => void;
            }

            /** One checkout step. `validate` gates advancing: return true, or an error message to block. */
            export interface CheckoutStep {
              id: string;
              label: string;
              validate?: () => boolean | string;
            }
            """;

    private static final String PRICING = """
            // GENERATED cart spine — pricing engine + rule factories, do not edit.
            import type { AdjustmentLine, CartItem, CartTotals, PricingRule } from './types';

            const round2 = (n: number): number => Math.round(n * 100) / 100;

            /**
             * Applies an ordered list of pricing rules to the subtotal. Adding tax, delivery or a
             * discount is a new rule in the list — this engine never changes (Open/Closed).
             */
            export function computeTotals(items: CartItem[], rules: PricingRule[]): CartTotals {
              const subtotal = round2(items.reduce((sum, i) => sum + i.unitPrice * i.quantity, 0));
              const adjustments: AdjustmentLine[] = [];
              let runningTotal = subtotal;
              for (const rule of rules) {
                const amount = round2(rule.apply({ items, subtotal, runningTotal }));
                if (amount !== 0) {
                  adjustments.push({ id: rule.id, label: rule.label, amount });
                  runningTotal = round2(runningTotal + amount);
                }
              }
              return { subtotal, adjustments, total: Math.max(0, runningTotal) };
            }

            // ── Rule factories: configure pricing without editing the engine ──────────────
            export const percentageFee = (id: string, label: string, rate: number): PricingRule => ({
              id,
              label,
              apply: ({ subtotal }) => subtotal * rate,
            });

            export const flatFee = (id: string, label: string, amount: number): PricingRule => ({
              id,
              label,
              apply: () => amount,
            });

            export const percentageDiscount = (id: string, label: string, rate: number): PricingRule => ({
              id,
              label,
              apply: ({ subtotal }) => -(subtotal * rate),
            });

            /** Wraps a rule so it is waived once the subtotal reaches a threshold (e.g. free delivery over N). */
            export const waivedOver = (rule: PricingRule, threshold: number): PricingRule => ({
              id: rule.id,
              label: rule.label,
              apply: (ctx) => (ctx.subtotal >= threshold ? 0 : rule.apply(ctx)),
            });
            """;

    private static final String STORAGE = """
            // GENERATED cart spine — persistence strategies, do not edit.
            import type { CartItem, CartStorage } from './types';

            export const localStorageCart = (key: string = 'cart'): CartStorage => ({
              load: () => {
                try {
                  const raw = localStorage.getItem(key);
                  return raw ? (JSON.parse(raw) as CartItem[]) : [];
                } catch {
                  return [];
                }
              },
              save: (items) => {
                try {
                  localStorage.setItem(key, JSON.stringify(items));
                } catch {
                  /* storage unavailable — cart stays in memory for this session */
                }
              },
            });

            export const memoryCart = (): CartStorage => {
              let state: CartItem[] = [];
              return {
                load: () => state,
                save: (items) => {
                  state = items;
                },
              };
            };
            """;

    private static final String CART_CONFIG = """
            // Cart configuration — EDIT THIS FILE to fit the business. The cart mechanism never changes;
            // all business-specific behaviour (pricing, persistence) is wired here.
            import type { PricingRule } from './types';
            import { localStorageCart } from './storage';

            // Where the cart is persisted. Swap for memoryCart() or a custom CartStorage.
            export const cartStorage = localStorageCart('cart');

            // Ordered price adjustments applied after the subtotal. Add/reorder freely — the engine
            // in pricing.ts never changes. To enable one, import the factory and add it here, e.g.:
            //   import { percentageFee, flatFee, percentageDiscount, waivedOver } from './pricing';
            //   export const pricingRules: PricingRule[] = [
            //     percentageFee('gst', 'GST (5%)', 0.05),
            //     waivedOver(flatFee('delivery', 'Delivery', 40), 500),
            //     percentageDiscount('launch', 'Launch offer (10%)', 0.10),
            //   ];
            export const pricingRules: PricingRule[] = [];
            """;

    private static final String CART_CONTEXT = """
            // GENERATED cart spine — canonical commerce provider (cart state only, headless), do not edit.
            import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
            import type { CartItem, CartTotals } from './types';
            import { computeTotals } from './pricing';
            import { cartStorage, pricingRules } from './cartConfig';

            export interface CartContextValue {
              cartItems: CartItem[];
              addItem: (item: Omit<CartItem, 'quantity'>, quantity?: number) => void;
              removeItem: (id: CartItem['id'], variantKey?: string) => void;
              setItemQuantity: (id: CartItem['id'], quantity: number, variantKey?: string) => void;
              clearCart: () => void;
              totals: CartTotals;
              cartCount: number;
            }

            const lineKey = (id: CartItem['id'], variantKey?: string): string => `${id}::${variantKey ?? ''}`;

            const CartContext = createContext<CartContextValue | undefined>(undefined);

            export const CartProvider = ({ children }: { children: ReactNode }) => {
              const [cartItems, setCartItems] = useState<CartItem[]>(() => cartStorage.load());

              useEffect(() => {
                cartStorage.save(cartItems);
              }, [cartItems]);

              const addItem = (item: Omit<CartItem, 'quantity'>, quantity: number = 1) => {
                setCartItems((prev) => {
                  const key = lineKey(item.id, item.variantKey);
                  const existing = prev.find((i) => lineKey(i.id, i.variantKey) === key);
                  if (existing) {
                    return prev.map((i) =>
                      lineKey(i.id, i.variantKey) === key ? { ...i, quantity: i.quantity + quantity } : i,
                    );
                  }
                  return [...prev, { ...item, quantity }];
                });
              };

              const removeItem = (id: CartItem['id'], variantKey?: string) =>
                setCartItems((prev) => prev.filter((i) => lineKey(i.id, i.variantKey) !== lineKey(id, variantKey)));

              const setItemQuantity = (id: CartItem['id'], quantity: number, variantKey?: string) =>
                setCartItems((prev) =>
                  quantity <= 0
                    ? prev.filter((i) => lineKey(i.id, i.variantKey) !== lineKey(id, variantKey))
                    : prev.map((i) =>
                        lineKey(i.id, i.variantKey) === lineKey(id, variantKey) ? { ...i, quantity } : i,
                      ),
                );

              const clearCart = () => setCartItems([]);

              const totals = useMemo(() => computeTotals(cartItems, pricingRules), [cartItems]);
              const cartCount = useMemo(() => cartItems.reduce((n, i) => n + i.quantity, 0), [cartItems]);

              return (
                <CartContext.Provider
                  value={{ cartItems, addItem, removeItem, setItemQuantity, clearCart, totals, cartCount }}
                >
                  {children}
                </CartContext.Provider>
              );
            };

            export const useCart = (): CartContextValue => {
              const ctx = useContext(CartContext);
              if (!ctx) throw new Error('useCart must be used within a CartProvider');
              return ctx;
            };
            """;

    private static final String USE_CHECKOUT = """
            // GENERATED cart spine — configurable multi-step checkout machine, do not edit.
            import { useState } from 'react';
            import type { CheckoutStep } from './types';

            export interface CheckoutController {
              steps: CheckoutStep[];
              current: CheckoutStep | undefined;
              index: number;
              isFirst: boolean;
              isLast: boolean;
              error: string | null;
              progress: number;
              next: () => boolean;
              back: () => void;
              goTo: (id: string) => void;
            }

            /**
             * Drives an ordered list of checkout steps — pass as many or as few as the business needs
             * (restaurant: delivery → payment → confirm; gym: schedule → member → payment; wholesale:
             * shipping → review → payment → confirm). Steps are data, so adding one never changes this
             * machine. Each step's optional validate() gates advancing.
             */
            export function useCheckout(steps: CheckoutStep[]): CheckoutController {
              const [index, setIndex] = useState(0);
              const [error, setError] = useState<string | null>(null);

              const current = steps[index];
              const isFirst = index === 0;
              const isLast = index >= steps.length - 1;

              const next = (): boolean => {
                const result = current?.validate ? current.validate() : true;
                if (result !== true) {
                  setError(typeof result === 'string' ? result : 'Please complete this step to continue.');
                  return false;
                }
                setError(null);
                if (!isLast) setIndex((i) => i + 1);
                return true;
              };

              const back = (): void => {
                setError(null);
                if (!isFirst) setIndex((i) => i - 1);
              };

              const goTo = (id: string): void => {
                const target = steps.findIndex((s) => s.id === id);
                if (target >= 0) {
                  setError(null);
                  setIndex(target);
                }
              };

              return {
                steps,
                current,
                index,
                isFirst,
                isLast,
                error,
                progress: steps.length ? (index + 1) / steps.length : 0,
                next,
                back,
                goTo,
              };
            }
            """;

    private static final String INDEX = """
            // GENERATED cart spine — public API barrel (import from '@/cart'), do not edit.
            // Headless by design: this framework ships state + logic only. Build all cart UI yourself
            // (add-to-cart control, quantity control, cart view) using useCart() and useCheckout().
            export type {
              CartItem,
              PricingRule,
              PricingContext,
              AdjustmentLine,
              CartTotals,
              CartStorage,
              CheckoutStep,
            } from './types';
            export { computeTotals, percentageFee, flatFee, percentageDiscount, waivedOver } from './pricing';
            export { localStorageCart, memoryCart } from './storage';
            export { CartProvider, useCart, type CartContextValue } from './CartContext';
            export { useCheckout, type CheckoutController } from './useCheckout';
            export { cartStorage, pricingRules } from './cartConfig';
            """;
}
