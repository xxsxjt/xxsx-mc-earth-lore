package org.anti_ad.mc.ipn.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Verbatim compile-only Inventory Profiles Next API annotation (MIT License).
 * Instructs IPN to completely ignore non-inventory screens or custom containers.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IPNIgnore {}
