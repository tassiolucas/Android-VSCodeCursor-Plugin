import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { parseDevices } from '../device/adbDevices';

describe('parseDevices', () => {
    const sampleOutput = [
        'List of devices attached',
        'emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1',
        'RF8NA1ABCDEF           offline usb:1-2 product:o1sxxx model:SM_G991B device:o1s transport_id:2',
        '',
        '',
    ].join('\n');

    it('parses serial, state and model from adb devices -l output', () => {
        const devices = parseDevices(sampleOutput);

        assert.equal(devices.length, 2);

        assert.deepEqual(devices[0], {
            serial: 'emulator-5554',
            state: 'device',
            model: 'sdk_gphone64_arm64',
        });

        assert.deepEqual(devices[1], {
            serial: 'RF8NA1ABCDEF',
            state: 'offline',
            model: 'SM_G991B',
        });
    });

    it('returns empty list when only the header line is present', () => {
        assert.deepEqual(parseDevices('List of devices attached\n'), []);
    });

    it('omits model when the line has no model: detail', () => {
        const output = 'List of devices attached\n0123456789ABCDEF       unauthorized transport_id:3\n';
        const devices = parseDevices(output);

        assert.equal(devices.length, 1);
        assert.equal(devices[0].serial, '0123456789ABCDEF');
        assert.equal(devices[0].state, 'unauthorized');
        assert.equal(devices[0].model, undefined);
    });
});
