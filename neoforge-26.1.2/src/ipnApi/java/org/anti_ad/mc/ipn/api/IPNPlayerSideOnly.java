package org.anti_ad.mc.ipn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Verbatim compile-only Inventory Profiles Next API annotation (MIT License).
 * Marks a Container/Menu or Screen so IPN only manages player inventory slots
 * and never injects storage sort/transfer buttons onto machine/functional GUIs.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IPNPlayerSideOnly {}
