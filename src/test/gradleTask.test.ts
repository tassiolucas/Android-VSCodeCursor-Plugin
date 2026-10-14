import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { qualifiedTask, variantTaskName } from '../build/gradleTaskNames';

describe('variantTaskName', () => {
    it('capitalizes the first character of the variant for install tasks', () => {
        assert.equal(variantTaskName('install', 'interHmgDebug'), 'installInterHmgDebug');
    });

    it('capitalizes the first character of the variant for assemble tasks', () => {
        assert.equal(variantTaskName('assemble', 'interMockDebug'), 'assembleInterMockDebug');
    });

    it('handles single-character variant names', () => {
        assert.equal(variantTaskName('install', 'd'), 'installD');
    });
});

describe('qualifiedTask', () => {
    it('joins Gradle module path and task name with a colon', () => {
        assert.equal(qualifiedTask(':app', 'installInterHmgDebug'), ':app:installInterHmgDebug');
    });

    it('works with nested module paths', () => {
        assert.equal(
            qualifiedTask(':core-modules:analytics', 'assembleDebug'),
            ':core-modules:analytics:assembleDebug',
        );
    });
});
