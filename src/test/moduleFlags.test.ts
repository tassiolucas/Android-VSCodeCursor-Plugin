import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { parseFlagKey, parseFlagsContent, toFlag } from '../profile/moduleFlags';

describe('parseFlagKey', () => {
    it('maps explicit Gradle group and nested path to gradlePath and relativePath', () => {
        const result = parseFlagKey('core-modules.core-modules/base-modules/analytics');
        assert.equal(result.gradlePath, ':core-modules:analytics');
        assert.equal(result.relativePath, 'core-modules/base-modules/analytics');
    });

    it('uses last path segment as module id when group is explicit', () => {
        const result = parseFlagKey('mkt-projects.digital-goods/domain/feature');
        assert.equal(result.gradlePath, ':mkt-projects:feature');
        assert.equal(result.relativePath, 'digital-goods/domain/feature');
    });

    it('derives gradlePath from last segment when there is no group prefix', () => {
        const result = parseFlagKey('app/src/module');
        assert.equal(result.gradlePath, ':module');
        assert.equal(result.relativePath, 'app/src/module');
    });

    it('treats dot before first slash as group separator', () => {
        const result = parseFlagKey('group.relative/nested/module');
        assert.equal(result.gradlePath, ':group:module');
        assert.equal(result.relativePath, 'relative/nested/module');
    });
});

describe('toFlag', () => {
    it('accepts true, false and auto case-insensitively with surrounding whitespace', () => {
        assert.equal(toFlag('true'), 'true');
        assert.equal(toFlag(' FALSE '), 'false');
        assert.equal(toFlag('Auto'), 'auto');
    });

    it('rejects invalid flag values', () => {
        assert.equal(toFlag('yes'), undefined);
        assert.equal(toFlag(''), undefined);
        assert.equal(toFlag('1'), undefined);
    });
});

describe('parseFlagsContent', () => {
    it('parses valid module flag lines into FlaggedModule entries', () => {
        const content = [
            'core-modules.core-modules/base-modules/analytics=auto',
            'mkt-projects.digital-goods/cart=true',
            'legacy-only/path/to/legacy=false',
        ].join('\n');

        const modules = parseFlagsContent(content);
        assert.equal(modules.length, 3);

        const analytics = modules.find(m => m.key === 'core-modules.core-modules/base-modules/analytics');
        assert.ok(analytics);
        assert.equal(analytics.flag, 'auto');
        assert.equal(analytics.gradlePath, ':core-modules:analytics');
        assert.equal(analytics.relativePath, 'core-modules/base-modules/analytics');

        assert.equal(modules.find(m => m.flag === 'true')?.gradlePath, ':mkt-projects:cart');
        assert.equal(modules.find(m => m.flag === 'false')?.gradlePath, ':legacy');
    });

    it('ignores sdk.dir, comments, blanks and keys without a slash', () => {
        const content = [
            '# module flags',
            '',
            'sdk.dir=/Users/me/Android/sdk',
            'app=true',
            '!ignored=auto',
            'valid.group/path/module=auto',
        ].join('\n');

        const modules = parseFlagsContent(content);
        assert.equal(modules.length, 1);
        assert.equal(modules[0].key, 'valid.group/path/module');
        assert.equal(modules[0].flag, 'auto');
    });

    it('skips lines with invalid flag values', () => {
        const content = 'group.path/module=maybe\nother/path/module=true\n';
        const modules = parseFlagsContent(content);
        assert.equal(modules.length, 1);
        assert.equal(modules[0].key, 'other/path/module');
    });
});
