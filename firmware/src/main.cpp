#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#include "ble_protocol.h"
#include "pins.h"

// Minimal BLE server: advertises as RoslinkiController and exposes the
// watering-command + status characteristics. Command parsing and pump/valve
// control are TODO — see docs/ble-protocol.md for the expected JSON payloads.

static BLECharacteristic* statusCharacteristic;
static volatile bool deviceConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override { deviceConnected = true; }
    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        server->getAdvertising()->start();
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* characteristic) override {
        String command = String(characteristic->getValue().c_str());
        Serial.printf("Command received: %s\n", command.c_str());
        // TODO: parse JSON per docs/ble-protocol.md and drive the pump/valve.
    }
};

void setup() {
    Serial.begin(115200);

    BLEDevice::init(BLE_DEVICE_NAME);
    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    BLEService* service = server->createService(SERVICE_UUID);

    BLECharacteristic* commandCharacteristic = service->createCharacteristic(
        COMMAND_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    commandCharacteristic->setCallbacks(new CommandCallbacks());

    statusCharacteristic = service->createCharacteristic(
        STATUS_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
    statusCharacteristic->addDescriptor(new BLE2902());

    service->start();

    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->start();

    Serial.println("BLE advertising as " BLE_DEVICE_NAME);
}

void loop() {
    // TODO: read soil moisture on PIN_SOIL_SENSOR, drive pump via PIN_PUMP,
    // notify status JSON on statusCharacteristic.
    delay(1000);
}
