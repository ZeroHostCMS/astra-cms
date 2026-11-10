package io.astra.spi.integration;

/**
 * Tier 1 extension point (slot {@code carrier.booking}): books a waybill with a last-mile carrier. Implementations
 * ship as isolated-JVM plugins and reach the carrier only through {@link PartnerHttp}.
 */
public interface CarrierAdapter {

    Booking book(Shipment shipment);

    /** What the platform hands the carrier: no PII beyond what the carrier needs to price and route. */
    record Shipment(String waybill, String order, String customer, String zone, String service, double weightKg, long codAmount) { }

    /** The carrier's answer; {@code eta} is empty when the carrier does not give one. */
    record Booking(String carrier, String tracking, long fee, String service, String eta) { }
}
