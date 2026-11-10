package io.astra.spi.pricing;

import java.time.LocalDate;

/**
 * The fact pricing rules match on: one parcel to quote. Rules read it through its getters ({@code zone},
 * {@code weightKg}, {@code express}…); it cannot be changed by a rule.
 */
public final class QuoteRequest {

    private final String zone;
    private final String service;
    private final double weightKg;
    private final double lengthCm;
    private final double widthCm;
    private final double heightCm;
    private final long codAmount;
    private final String customer;
    private final String segment;
    private final LocalDate date;

    public QuoteRequest(String zone, String service, double weightKg, double lengthCm, double widthCm, double heightCm,
                        long codAmount, String customer, String segment, LocalDate date) {
        this.zone = zone == null ? "Domestic" : zone;
        this.service = service == null ? "Standard" : service;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.codAmount = codAmount;
        this.customer = customer;
        this.segment = segment;
        this.date = date == null ? LocalDate.now() : date;
    }

    public String getZone() { return zone; }
    public String getService() { return service; }
    public boolean isExpress() { return "Express".equals(service); }
    public double getWeightKg() { return weightKg; }
    public double getLengthCm() { return lengthCm; }
    public double getWidthCm() { return widthCm; }
    public double getHeightCm() { return heightCm; }
    /** Volumetric weight {@code L×W×H / 6000} in kg; 0 when the dimensions are unknown. */
    public double getVolumetricKg() { return lengthCm * widthCm * heightCm / 6000.0; }
    public long getCodAmount() { return codAmount; }
    public String getCustomer() { return customer; }
    public String getSegment() { return segment; }
    public LocalDate getDate() { return date; }
    /** Day of month and month, for date-bound rules such as a 11.11 surcharge. */
    public int getDay() { return date.getDayOfMonth(); }
    public int getMonth() { return date.getMonthValue(); }
}
