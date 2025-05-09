/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.homeassistant.internal.component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.generic.ChannelStateUpdateListener;
import org.openhab.binding.mqtt.generic.values.NumberValue;
import org.openhab.binding.mqtt.generic.values.OnOffValue;
import org.openhab.binding.mqtt.generic.values.TextValue;
import org.openhab.binding.mqtt.generic.values.Value;
import org.openhab.binding.mqtt.homeassistant.internal.ComponentChannel;
import org.openhab.binding.mqtt.homeassistant.internal.ComponentChannelType;
import org.openhab.binding.mqtt.homeassistant.internal.config.dto.AbstractChannelConfiguration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

import com.google.gson.annotations.SerializedName;

/**
 * A MQTT climate component, following the https://www.home-assistant.io/components/climate.mqtt/ specification.
 *
 * @author David Graeff - Initial contribution
 * @author Anton Kharuzhy - Implementation
 * @author Vaclav Cejka - added support for humidity and preset_modes
 */
@NonNullByDefault
public class Climate extends AbstractComponent<Climate.ChannelConfiguration> {
    public static final String ACTION_CH_ID = "action";
    public static final String AUX_CH_ID = "aux";
    public static final String AWAY_MODE_CH_ID = "away-mode";
    public static final String CURRENT_HUMIDITY_CH_ID = "current-humidity";
    public static final String CURRENT_TEMPERATURE_CH_ID = "current-temperature";
    public static final String FAN_MODE_CH_ID = "fan-mode";
    public static final String HOLD_CH_ID = "hold";
    public static final String MODE_CH_ID = "mode";
    public static final String PRESET_MODE_CH_ID = "preset-mode";
    public static final String SWING_CH_ID = "swing";
    public static final String TARGET_HUMIDITY_CH_ID = "target-humidity";
    public static final String TEMPERATURE_CH_ID = "temperature";
    public static final String TEMPERATURE_HIGH_CH_ID = "temperature-high";
    public static final String TEMPERATURE_LOW_CH_ID = "temperature-low";
    public static final String POWER_CH_ID = "power";

    private static final String ACTION_OFF = "off";
    private static final State ACTION_OFF_STATE = new StringType(ACTION_OFF);
    private static final List<String> ACTION_MODES = List.of(ACTION_OFF, "heating", "cooling", "drying", "idle", "fan");

    private static final String FAN_MODE_AUTO = "auto";
    private static final String FAN_MODE_LOW = "low";
    private static final String FAN_MODE_MEDIUM = "medium";
    private static final String FAN_MODE_HIGH = "high";

    private static final Map<String, String> FAN_MODE_LABELS = Map.of(FAN_MODE_AUTO,
            "@text/state.climate.fan-mode.auto", FAN_MODE_LOW, "@text/state.climate.fan-mode.low", FAN_MODE_MEDIUM,
            "@text/state.climate.fan-mode.medium", FAN_MODE_HIGH, "@text/state.climate.fan-mode.high");

    private static final String MODE_AUTO = "auto";
    private static final String MODE_OFF = "off";
    private static final String MODE_COOL = "cool";
    private static final String MODE_HEAT = "heat";
    private static final String MODE_DRY = "dry";
    private static final String MODE_FAN_ONLY = "fan_only";

    private static final Map<String, String> MODE_LABELS = Map.of(MODE_AUTO, "@text/state.climate.mode.auto", MODE_OFF,
            "@text/state.climate.mode.off", MODE_COOL, "@text/state.climate.mode.cool", MODE_HEAT,
            "@text/state.climate.mode.heat", MODE_DRY, "@text/state.climate.mode.dry", MODE_FAN_ONLY,
            "@text/state.climate.mode.fan-only");

    private static final String SWING_MODE_ON = "on";
    private static final String SWING_MODE_OFF = "off";

    private static final Map<String, String> SWING_MODE_LABELS = Map.of(SWING_MODE_ON,
            "@text/state.climate.swing-mode.on", SWING_MODE_OFF, "@text/state.climate.swing-mode.off");

    /**
     * Configuration class for MQTT component
     */
    static class ChannelConfiguration extends AbstractChannelConfiguration {
        ChannelConfiguration() {
            super("MQTT HVAC");
        }

        protected @Nullable Boolean optimistic;

        @SerializedName("action_template")
        protected @Nullable String actionTemplate;
        @SerializedName("action_topic")
        protected @Nullable String actionTopic;

        @SerializedName("aux_command_topic")
        protected @Nullable String auxCommandTopic;
        @SerializedName("aux_state_template")
        protected @Nullable String auxStateTemplate;
        @SerializedName("aux_state_topic")
        protected @Nullable String auxStateTopic;

        @SerializedName("away_mode_command_topic")
        protected @Nullable String awayModeCommandTopic;
        @SerializedName("away_mode_state_template")
        protected @Nullable String awayModeStateTemplate;
        @SerializedName("away_mode_state_topic")
        protected @Nullable String awayModeStateTopic;

        @SerializedName("current_humidity_template")
        protected @Nullable String currentHumidityTemplate;
        @SerializedName("current_humidity_topic")
        protected @Nullable String currentHumidityTopic;

        @SerializedName("current_temperature_template")
        protected @Nullable String currentTemperatureTemplate;
        @SerializedName("current_temperature_topic")
        protected @Nullable String currentTemperatureTopic;

        @SerializedName("fan_mode_command_template")
        protected @Nullable String fanModeCommandTemplate;
        @SerializedName("fan_mode_command_topic")
        protected @Nullable String fanModeCommandTopic;
        @SerializedName("fan_mode_state_template")
        protected @Nullable String fanModeStateTemplate;
        @SerializedName("fan_mode_state_topic")
        protected @Nullable String fanModeStateTopic;
        @SerializedName("fan_modes")
        protected List<String> fanModes = List.of(FAN_MODE_AUTO, FAN_MODE_LOW, FAN_MODE_MEDIUM, FAN_MODE_HIGH);

        @SerializedName("hold_command_template")
        protected @Nullable String holdCommandTemplate;
        @SerializedName("hold_command_topic")
        protected @Nullable String holdCommandTopic;
        @SerializedName("hold_state_template")
        protected @Nullable String holdStateTemplate;
        @SerializedName("hold_state_topic")
        protected @Nullable String holdStateTopic;
        @SerializedName("hold_modes")
        protected @Nullable List<String> holdModes; // Are there default modes? Now the channel will be ignored without
                                                    // hold modes.

        @SerializedName("mode_command_template")
        protected @Nullable String modeCommandTemplate;
        @SerializedName("mode_command_topic")
        protected @Nullable String modeCommandTopic;
        @SerializedName("mode_state_template")
        protected @Nullable String modeStateTemplate;
        @SerializedName("mode_state_topic")
        protected @Nullable String modeStateTopic;
        protected List<String> modes = List.of(MODE_AUTO, MODE_OFF, MODE_COOL, MODE_HEAT, MODE_DRY, MODE_FAN_ONLY);

        @SerializedName("preset_mode_command_template")
        protected @Nullable String presetModeCommandTemplate;
        @SerializedName("preset_mode_command_topic")
        protected @Nullable String presetModeCommandTopic;
        @SerializedName("preset_mode_state_topic")
        protected @Nullable String presetModeStateTopic;
        @SerializedName("preset_mode_value_template")
        protected @Nullable String presetModeStateTemplate;
        @SerializedName("preset_modes")
        protected List<String> presetModes = List.of(); // defaults heavily depend on the
                                                        // type of the device

        @SerializedName("swing_command_template")
        protected @Nullable String swingCommandTemplate;
        @SerializedName("swing_command_topic")
        protected @Nullable String swingCommandTopic;
        @SerializedName("swing_state_template")
        protected @Nullable String swingStateTemplate;
        @SerializedName("swing_state_topic")
        protected @Nullable String swingStateTopic;
        @SerializedName("swing_modes")
        protected List<String> swingModes = List.of(SWING_MODE_ON, SWING_MODE_OFF);

        @SerializedName("target_humidity_command_template")
        protected @Nullable String targetHumidityCommandTemplate;
        @SerializedName("target_humidity_command_topic")
        protected @Nullable String targetHumidityCommandTopic;
        @SerializedName("target_humidity_state_template")
        protected @Nullable String targetHumidityStateTemplate;
        @SerializedName("target_humidity_state_topic")
        protected @Nullable String targetHumidityStateTopic;

        @SerializedName("temperature_command_template")
        protected @Nullable String temperatureCommandTemplate;
        @SerializedName("temperature_command_topic")
        protected @Nullable String temperatureCommandTopic;
        @SerializedName("temperature_state_template")
        protected @Nullable String temperatureStateTemplate;
        @SerializedName("temperature_state_topic")
        protected @Nullable String temperatureStateTopic;

        @SerializedName("temperature_high_command_template")
        protected @Nullable String temperatureHighCommandTemplate;
        @SerializedName("temperature_high_command_topic")
        protected @Nullable String temperatureHighCommandTopic;
        @SerializedName("temperature_high_state_template")
        protected @Nullable String temperatureHighStateTemplate;
        @SerializedName("temperature_high_state_topic")
        protected @Nullable String temperatureHighStateTopic;

        @SerializedName("temperature_low_command_template")
        protected @Nullable String temperatureLowCommandTemplate;
        @SerializedName("temperature_low_command_topic")
        protected @Nullable String temperatureLowCommandTopic;
        @SerializedName("temperature_low_state_template")
        protected @Nullable String temperatureLowStateTemplate;
        @SerializedName("temperature_low_state_topic")
        protected @Nullable String temperatureLowStateTopic;

        @SerializedName("power_command_topic")
        protected @Nullable String powerCommandTopic;

        @SerializedName("max_humidity")
        protected BigDecimal maxHumidity = new BigDecimal(99);
        @SerializedName("min_humidity")
        protected BigDecimal minHumidity = new BigDecimal(30);

        protected Integer initial = 21;
        @SerializedName("max_temp")
        protected @Nullable BigDecimal maxTemp;
        @SerializedName("min_temp")
        protected @Nullable BigDecimal minTemp;
        @SerializedName("temperature_unit")
        protected @Nullable TemperatureUnit temperatureUnit;
        @SerializedName("temp_step")
        protected BigDecimal tempStep = BigDecimal.ONE;
        protected @Nullable BigDecimal precision;
        @SerializedName("send_if_off")
        protected Boolean sendIfOff = true;
    }

    public Climate(ComponentFactory.ComponentConfiguration componentConfiguration) {
        super(componentConfiguration, ChannelConfiguration.class);

        TemperatureUnit temperatureUnit = channelConfiguration.temperatureUnit;
        if (channelConfiguration.temperatureUnit == null) {
            if (ImperialUnits.FAHRENHEIT.equals(componentConfiguration.getUnitProvider().getUnit(Temperature.class))) {
                temperatureUnit = TemperatureUnit.FAHRENHEIT;
            } else {
                temperatureUnit = TemperatureUnit.CELSIUS;
            }
        }
        BigDecimal precision = channelConfiguration.precision != null ? channelConfiguration.precision
                : temperatureUnit.getDefaultPrecision();
        final ChannelStateUpdateListener updateListener = componentConfiguration.getUpdateListener();

        ComponentChannel actionChannel = buildOptionalChannel(ACTION_CH_ID, ComponentChannelType.STRING,
                new TextValue(ACTION_MODES.toArray(new String[0])), updateListener, null, null,
                channelConfiguration.actionTemplate, channelConfiguration.actionTopic, null);

        final Predicate<Command> commandFilter = channelConfiguration.sendIfOff ? null
                : getCommandFilter(actionChannel);

        buildOptionalChannel(AUX_CH_ID, ComponentChannelType.SWITCH, new OnOffValue(), updateListener, null,
                channelConfiguration.auxCommandTopic, channelConfiguration.auxStateTemplate,
                channelConfiguration.auxStateTopic, commandFilter);

        buildOptionalChannel(AWAY_MODE_CH_ID, ComponentChannelType.SWITCH, new OnOffValue(), updateListener, null,
                channelConfiguration.awayModeCommandTopic, channelConfiguration.awayModeStateTemplate,
                channelConfiguration.awayModeStateTopic, commandFilter);

        buildOptionalChannel(CURRENT_HUMIDITY_CH_ID, ComponentChannelType.HUMIDITY,
                new NumberValue(new BigDecimal(0), new BigDecimal(100), null, Units.PERCENT), updateListener, null,
                null, channelConfiguration.currentHumidityTemplate, channelConfiguration.currentHumidityTopic, null);

        buildOptionalChannel(CURRENT_TEMPERATURE_CH_ID, ComponentChannelType.TEMPERATURE,
                new NumberValue(null, null, precision, temperatureUnit.getUnit()), updateListener, null, null,
                channelConfiguration.currentTemperatureTemplate, channelConfiguration.currentTemperatureTopic,
                commandFilter);

        Map<String, String> modes = channelConfiguration.fanModes.stream()
                .collect(Collectors.toMap(m -> m, m -> m, (a, b) -> a, LinkedHashMap::new));
        buildOptionalChannel(FAN_MODE_CH_ID, ComponentChannelType.STRING,
                new TextValue(modes, modes, FAN_MODE_LABELS, FAN_MODE_LABELS), updateListener,
                channelConfiguration.fanModeCommandTemplate, channelConfiguration.fanModeCommandTopic,
                channelConfiguration.fanModeStateTemplate, channelConfiguration.fanModeStateTopic, commandFilter);

        List<String> holdModes = channelConfiguration.holdModes;
        if (holdModes != null && !holdModes.isEmpty()) {
            buildOptionalChannel(HOLD_CH_ID, ComponentChannelType.STRING,
                    new TextValue(holdModes.toArray(new String[0])), updateListener,
                    channelConfiguration.holdCommandTemplate, channelConfiguration.holdCommandTopic,
                    channelConfiguration.holdStateTemplate, channelConfiguration.holdStateTopic, commandFilter);
        }

        modes = channelConfiguration.modes.stream()
                .collect(Collectors.toMap(m -> m, m -> m, (a, b) -> a, LinkedHashMap::new));
        buildOptionalChannel(MODE_CH_ID, ComponentChannelType.STRING,
                new TextValue(modes, modes, MODE_LABELS, MODE_LABELS), updateListener,
                channelConfiguration.modeCommandTemplate, channelConfiguration.modeCommandTopic,
                channelConfiguration.modeStateTemplate, channelConfiguration.modeStateTopic, commandFilter);

        buildOptionalChannel(PRESET_MODE_CH_ID, ComponentChannelType.STRING,
                new TextValue(channelConfiguration.presetModes.toArray(new String[0])), updateListener,
                channelConfiguration.presetModeCommandTemplate, channelConfiguration.presetModeCommandTopic,
                channelConfiguration.presetModeStateTemplate, channelConfiguration.presetModeStateTopic, commandFilter);

        modes = channelConfiguration.swingModes.stream()
                .collect(Collectors.toMap(m -> m, m -> m, (a, b) -> a, LinkedHashMap::new));
        buildOptionalChannel(SWING_CH_ID, ComponentChannelType.STRING,
                new TextValue(modes, modes, SWING_MODE_LABELS, SWING_MODE_LABELS), updateListener,
                channelConfiguration.swingCommandTemplate, channelConfiguration.swingCommandTopic,
                channelConfiguration.swingStateTemplate, channelConfiguration.swingStateTopic, commandFilter);

        buildOptionalChannel(TARGET_HUMIDITY_CH_ID, ComponentChannelType.HUMIDITY,
                new NumberValue(channelConfiguration.minHumidity, channelConfiguration.maxHumidity, null,
                        Units.PERCENT),
                updateListener, channelConfiguration.targetHumidityCommandTemplate,
                channelConfiguration.targetHumidityCommandTopic, channelConfiguration.targetHumidityStateTemplate,
                channelConfiguration.targetHumidityStateTopic, commandFilter);

        buildOptionalChannel(TEMPERATURE_CH_ID, ComponentChannelType.TEMPERATURE,
                new NumberValue(channelConfiguration.minTemp, channelConfiguration.maxTemp,
                        channelConfiguration.tempStep, temperatureUnit.getUnit()),
                updateListener, channelConfiguration.temperatureCommandTemplate,
                channelConfiguration.temperatureCommandTopic, channelConfiguration.temperatureStateTemplate,
                channelConfiguration.temperatureStateTopic, commandFilter);

        buildOptionalChannel(TEMPERATURE_HIGH_CH_ID, ComponentChannelType.TEMPERATURE,
                new NumberValue(channelConfiguration.minTemp, channelConfiguration.maxTemp,
                        channelConfiguration.tempStep, temperatureUnit.getUnit()),
                updateListener, channelConfiguration.temperatureHighCommandTemplate,
                channelConfiguration.temperatureHighCommandTopic, channelConfiguration.temperatureHighStateTemplate,
                channelConfiguration.temperatureHighStateTopic, commandFilter);

        buildOptionalChannel(TEMPERATURE_LOW_CH_ID, ComponentChannelType.TEMPERATURE,
                new NumberValue(channelConfiguration.minTemp, channelConfiguration.maxTemp,
                        channelConfiguration.tempStep, temperatureUnit.getUnit()),
                updateListener, channelConfiguration.temperatureLowCommandTemplate,
                channelConfiguration.temperatureLowCommandTopic, channelConfiguration.temperatureLowStateTemplate,
                channelConfiguration.temperatureLowStateTopic, commandFilter);

        buildOptionalChannel(POWER_CH_ID, ComponentChannelType.SWITCH, new OnOffValue(), updateListener, null,
                channelConfiguration.powerCommandTopic, null, null, null);

        finalizeChannels();
    }

    @Nullable
    private ComponentChannel buildOptionalChannel(String channelId, ComponentChannelType channelType, Value valueState,
            ChannelStateUpdateListener channelStateUpdateListener, @Nullable String commandTemplate,
            @Nullable String commandTopic, @Nullable String stateTemplate, @Nullable String stateTopic,
            @Nullable Predicate<Command> commandFilter) {
        if ((commandTopic != null && !commandTopic.isBlank()) || (stateTopic != null && !stateTopic.isBlank())) {
            return buildChannel(channelId, channelType, valueState, getName(), channelStateUpdateListener)
                    .stateTopic(stateTopic, stateTemplate, channelConfiguration.getValueTemplate())
                    .commandTopic(commandTopic, channelConfiguration.isRetain(), channelConfiguration.getQos(),
                            commandTemplate)
                    .inferOptimistic(channelConfiguration.optimistic).commandFilter(commandFilter).build();
        }
        return null;
    }

    private @Nullable Predicate<Command> getCommandFilter(@Nullable ComponentChannel actionChannel) {
        if (actionChannel == null) {
            return null;
        }
        final var val = actionChannel.getState().getCache();
        return command -> !ACTION_OFF_STATE.equals(val.getChannelState());
    }
}
