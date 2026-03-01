package com.bones.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jarvis.billing")
public class BillingProperties {

    private boolean enabled = true;

    private double defaultInputCostPer1k = 0.001;

    private double defaultOutputCostPer1k = 0.002;

    private Map<String, ProviderPricing> providers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getDefaultInputCostPer1k() {
        return defaultInputCostPer1k;
    }

    public void setDefaultInputCostPer1k(double defaultInputCostPer1k) {
        this.defaultInputCostPer1k = defaultInputCostPer1k;
    }

    public double getDefaultOutputCostPer1k() {
        return defaultOutputCostPer1k;
    }

    public void setDefaultOutputCostPer1k(double defaultOutputCostPer1k) {
        this.defaultOutputCostPer1k = defaultOutputCostPer1k;
    }

    public Map<String, ProviderPricing> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderPricing> providers) {
        this.providers = providers;
    }

    public Pricing resolve(String provider, String model) {
        String safeProvider = normalize(provider, "unknown");
        String safeModel = normalize(model, "default");

        ProviderPricing providerPricing = providers.get(safeProvider);
        if (providerPricing == null || providerPricing.getModels() == null) {
            return new Pricing(defaultInputCostPer1k, defaultOutputCostPer1k);
        }

        ModelPricing modelPricing = providerPricing.getModels().get(safeModel);
        if (modelPricing == null) {
            return new Pricing(defaultInputCostPer1k, defaultOutputCostPer1k);
        }

        double input = modelPricing.getInputCostPer1k() > 0 ? modelPricing.getInputCostPer1k() : defaultInputCostPer1k;
        double output = modelPricing.getOutputCostPer1k() > 0 ? modelPricing.getOutputCostPer1k() : defaultOutputCostPer1k;
        return new Pricing(input, output);
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase();
    }

    public record Pricing(double inputCostPer1k, double outputCostPer1k) {
    }

    public static class ProviderPricing {

        private Map<String, ModelPricing> models = new LinkedHashMap<>();

        public Map<String, ModelPricing> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelPricing> models) {
            this.models = models;
        }
    }

    public static class ModelPricing {

        private double inputCostPer1k;

        private double outputCostPer1k;

        public double getInputCostPer1k() {
            return inputCostPer1k;
        }

        public void setInputCostPer1k(double inputCostPer1k) {
            this.inputCostPer1k = inputCostPer1k;
        }

        public double getOutputCostPer1k() {
            return outputCostPer1k;
        }

        public void setOutputCostPer1k(double outputCostPer1k) {
            this.outputCostPer1k = outputCostPer1k;
        }
    }
}
