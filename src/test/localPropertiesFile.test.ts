import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { applyModuleFlags } from '../modules/localPropertiesFile';

describe('applyModuleFlags', () => {
    const baseContent = [
        '# Android SDK',
        'sdk.dir=/Users/me/Library/Android/sdk',
        '',
        'core-modules.core-modules/base-modules/analytics=auto',
        'mkt-projects.digital-goods/cart=false',
    ].join('\n');

    it('updates an existing flag while preserving sdk.dir, comments and line order', () => {
        const changes = new Map<string, 'true' | 'false' | 'auto' | undefined>([
            ['mkt-projects.digital-goods/cart', 'true'],
        ]);

        const result = applyModuleFlags(`${baseContent}\n`, changes);

        assert.match(result, /^# Android SDK/);
        assert.match(result, /sdk\.dir=\/Users\/me\/Library\/Android\/sdk/);
        assert.match(result, /core-modules\.core-modules\/base-modules\/analytics=auto/);
        assert.match(result, /mkt-projects\.digital-goods\/cart=true/);
        assert.doesNotMatch(result, /cart=false/);
        const analyticsIndex = result.indexOf('analytics=auto');
        const cartIndex = result.indexOf('cart=true');
        assert.ok(analyticsIndex < cartIndex);
    });

    it('removes a module flag when change value is undefined', () => {
        const changes = new Map<string, 'true' | 'false' | 'auto' | undefined>([
            ['core-modules.core-modules/base-modules/analytics', undefined],
        ]);

        const result = applyModuleFlags(`${baseContent}\n`, changes);

        assert.doesNotMatch(result, /analytics=/);
        assert.match(result, /sdk\.dir=/);
        assert.match(result, /cart=false/);
    });

    it('appends new flags at the end and adds a trailing newline', () => {
        const changes = new Map<string, 'true' | 'false' | 'auto' | undefined>([
            ['new-group.new/path/module', 'auto'],
        ]);

        const result = applyModuleFlags(baseContent, changes);

        assert.match(result, /new-group\.new\/path\/module=auto\n$/);
        assert.ok(result.endsWith('\n'));
    });

    it('preserves CRLF line endings when input uses CRLF', () => {
        const crlf = 'sdk.dir=C:\\\\Android\\\\sdk\r\ngroup.path/module=false\r\n';
        const changes = new Map<string, 'true' | 'false' | 'auto' | undefined>([
            ['group.path/module', 'true'],
        ]);

        const result = applyModuleFlags(crlf, changes);

        assert.ok(result.includes('\r\n'));
        assert.match(result, /group\.path\/module=true/);
    });

    it('returns empty string when all lines are removed and nothing is appended', () => {
        const content = 'only.group/x/module=true\n';
        const changes = new Map<string, 'true' | 'false' | 'auto' | undefined>([
            ['only.group/x/module', undefined],
        ]);

        assert.equal(applyModuleFlags(content, changes), '');
    });
});
