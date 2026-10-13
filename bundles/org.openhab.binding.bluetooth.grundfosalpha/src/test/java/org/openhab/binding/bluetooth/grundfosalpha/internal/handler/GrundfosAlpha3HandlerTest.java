/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.bluetooth.grundfosalpha.internal.handler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.*;
import static org.openhab.binding.bluetooth.grundfosalpha.internal.GrundfosAlphaBindingConstants.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDevice;
import org.openhab.binding.bluetooth.BluetoothDevice.ConnectionState;
import org.openhab.binding.bluetooth.BluetoothService;
import org.openhab.binding.bluetooth.grundfosalpha.internal.protocol.CRC16Calculator;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.types.RefreshType;
import org.openhab.core.util.HexUtils;

/**
 * Tests for {@link GrundfosAlpha3Handler}.
 *
 * @author Jacob Laursen - Initial contribution
 * @author Jacob Laursen - Cover motor current and operating counters
 * @author Jacob Laursen - Cover writable modes and request sequencing
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class GrundfosAlpha3HandlerTest {
    private @Mock @NonNullByDefault({}) Thing thing;
    private @Mock @NonNullByDefault({}) ThingHandlerCallback callback;

    private @Mock @NonNullByDefault({}) BluetoothDevice device;
    private @Mock @NonNullByDefault({}) ScheduledExecutorService scheduler;
    private final Queue<Runnable> sends = new ArrayDeque<>();
    private @NonNullByDefault({}) GrundfosAlpha3Handler handler;

    @BeforeEach
    void prepareHandler() {
        when(thing.getUID()).thenReturn(new ThingUID(THING_TYPE_ALPHA3, "dummy"));
        when(thing.getConfiguration()).thenReturn(new Configuration());
        when(device.getConnectionState()).thenReturn(ConnectionState.CONNECTED);
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);
        var service = mock(BluetoothService.class);
        when(service.getUuid()).thenReturn(UUID.fromString("0000fe5d-0000-1000-8000-00805f9b34fb"));
        when(service.getCharacteristics()).thenReturn(List.of(characteristic));
        when(device.getServices()).thenReturn(List.of(service));
        lenient().when(device.getCharacteristic(characteristic.getUuid())).thenReturn(characteristic);
        lenient().when(device.writeCharacteristic(eq(characteristic), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(scheduler.schedule(any(Runnable.class), eq(700L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> {
                    sends.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        lenient().when(scheduler.schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        handler = new TestHandler(thing, scheduler, device);
        handler.setCallback(callback);
        handler.onServicesDiscovered();
        clearInvocations(callback);
    }

    @AfterEach
    void disposeHandler() {
        handler.dispose();
    }

    private void request(String channel) {
        handler.handleCommand(new ChannelUID(thing.getUID(), channel), RefreshType.REFRESH);
        sends.remove().run();
    }

    @ParameterizedTest
    @CsvSource({ "operating-mode,0,14", "operating-mode,1,14", "operating-mode,2,14", "operating-mode,3,14",
            "control-mode,0,15", "control-mode,1,15", "control-mode,2,15", "control-mode,13,15", "control-mode,14,15",
            "control-mode,15,15" })
    void modeCommandsPublishOnlyPumpReadings(String channel, int value, int offset) {
        var uid = new ChannelUID(thing.getUID(), channel);
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);
        handler.handleCommand(uid, new DecimalType(value));
        verifyNoInteractions(callback);
        sends.remove().run();
        byte[] response = HexUtils.hexToBytes("2412F8E70A0E00012F0100000700000046E5C600C536");
        handler.onCharacteristicUpdate(characteristic, response);
        verify(callback).stateUpdated(uid, DecimalType.ZERO);
        clearInvocations(callback);
        sends.remove().run();
        handler.onCharacteristicUpdate(characteristic, HexUtils.hexToBytes("2405F8E70A0100AEA2"));
        verifyNoInteractions(callback);
        sends.remove().run();
        response[offset] = (byte) value;
        CRC16Calculator.put(response, 18);
        handler.onCharacteristicUpdate(characteristic, response);
        verify(callback).stateUpdated(uid, new DecimalType(value));
        verifyNoMoreInteractions(callback);
    }

    @ParameterizedTest
    @CsvSource({ "operating-mode,-1", "operating-mode,4", "operating-mode,1.5", "operating-mode,4294967296",
            "control-mode,3", "control-mode,12", "control-mode,16", "control-mode,13.1" })
    void invalidModeCommandsDoNotSendRequests(String channel, String value) {
        handler.handleCommand(new ChannelUID(thing.getUID(), channel), new DecimalType(value));
        assertThat(sends.isEmpty(), is(true));
        verify(device, never()).writeCharacteristic(any(), any());
        verifyNoInteractions(callback);
    }

    @Test
    void disconnectedModeCommandsAreNotQueuedForReconnect() {
        when(device.getConnectionState()).thenReturn(ConnectionState.DISCONNECTED);
        handler.handleCommand(new ChannelUID(thing.getUID(), CHANNEL_OPERATING_MODE), new DecimalType(1));
        when(device.getConnectionState()).thenReturn(ConnectionState.CONNECTED);
        handler.onServicesDiscovered();
        assertThat(sends.isEmpty(), is(true));
        verify(device, never()).writeCharacteristic(any(), any());
    }

    private static class TestHandler extends GrundfosAlpha3Handler {
        TestHandler(Thing thing, ScheduledExecutorService scheduler, BluetoothDevice device) {
            super(thing, scheduler);
            this.device = device;
        }

        @Override
        public void disconnect() {
            // The mock device does not need the base handler's connection executor.
        }
    }

    @ParameterizedTest
    @CsvSource({ "0,0.000", "3600000,1.000", "444444440400,123456.789", "1364284386.704,378.968", "3601799.9996,1.000",
            "3601800,1.001", "3601800.0004,1.001" })
    void energyIsPublishedInKilowattHours(double joules, String kilowattHours) {
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);
        byte[] response = ByteBuffer.allocate(41).put(HexUtils.hexToBytes("2425F8E70A210000E80100001A"))
                .putDouble(joules).array();
        CRC16Calculator.put(response, 37);

        request(CHANNEL_ENERGY);
        handler.onCharacteristicUpdate(characteristic, response);

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_ENERGY),
                new QuantityType<>(new BigDecimal(kilowattHours), Units.KILOWATT_HOUR));
        verifyNoMoreInteractions(callback);
    }

    @Test
    void countersArePublishedAsSecondsAndStartCount() {
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);
        byte[] response = HexUtils.hexToBytes("241FF8E70A1B0000F80100001400000029000000130007C46A0000F82E000000FC0000");
        CRC16Calculator.put(response, 31);

        request(CHANNEL_OPERATING_TIME);
        handler.onCharacteristicUpdate(characteristic, response);

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_OPERATING_TIME),
                new QuantityType<>(509034, Units.SECOND));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_START_COUNT), new DecimalType(41));
        verifyNoMoreInteractions(callback);
    }

    @Test
    void electricalReadingsArePublishedInTheirMeasurementUnits() {
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);
        byte[] response = HexUtils.hexToBytes(
                "2430F8E70A2C000100010000254357878B439781803D21B00040F19C0040EA4A404536FDB4FFC00000421C000042040000017317");

        request(CHANNEL_MOTOR_CURRENT);
        handler.onCharacteristicUpdate(characteristic, response);

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MOTOR_CURRENT),
                new QuantityType<>(new BigDecimal("0.039"), Units.AMPERE));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_VOLTAGE_AC),
                new QuantityType<>(new BigDecimal("215.5"), Units.VOLT));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_POWER),
                new QuantityType<>(new BigDecimal("7.6"), Units.WATT));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_MOTOR_SPEED),
                new QuantityType<>(2928, Units.RPM));
        verifyNoMoreInteractions(callback);
    }

    @Test
    void flowAndHeadRetainTheirPublishedUnitsAndPrecision() {
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);

        request(CHANNEL_FLOW_RATE);
        handler.onCharacteristicUpdate(characteristic,
                HexUtils.hexToBytes("2423F8E70A1F000130010000183952A66C468F48AC7FFFFFFF7FFFFFFF41FF21397FFFFFFF44A8"));

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_FLOW_RATE),
                new QuantityType<>(new BigDecimal("0.723"), Units.CUBICMETRE_PER_HOUR));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_PUMP_HEAD),
                new QuantityType<>(new BigDecimal("1.83403"), SIUnits.METRE));
        verifyNoMoreInteractions(callback);
    }

    @Test
    void version2CounterNotificationsPublishBothChannels() {
        var characteristic = new BluetoothCharacteristic(UUID.fromString("859cffd1-036e-432a-aa28-1a0085b87ba9"), 1);

        request(CHANNEL_OPERATING_TIME);
        handler.onCharacteristicUpdate(characteristic, HexUtils.hexToBytes("2427F8E70A230000F80200001C00000041000000"));
        handler.onCharacteristicUpdate(characteristic, HexUtils.hexToBytes("000DA4BDDD00015128000DEC80000000410DA4BE"));
        verifyNoInteractions(callback);
        handler.onCharacteristicUpdate(characteristic, HexUtils.hexToBytes("0921B8"));

        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_OPERATING_TIME),
                new QuantityType<>(228900317, Units.SECOND));
        verify(callback).stateUpdated(new ChannelUID(thing.getUID(), CHANNEL_START_COUNT), new DecimalType(65));
        verifyNoMoreInteractions(callback);
    }
}
