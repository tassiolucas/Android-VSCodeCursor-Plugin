import * as fs from 'fs';
import * as path from 'path';

const OUTPUT_METADATA_FILE = 'output-metadata.json';
const MAX_SEARCH_DEPTH = 4;

interface OutputMetadata {
    applicationId?: string;
    variantName?: string;
    elements?: Array<{ outputFile?: string }>;
}

export interface BuiltApk {
    applicationId: string;
    apkPath?: string;
}

export function findBuiltApk(params: { appModuleDir: string; variant: string }): BuiltApk | undefined {
    const apkRoot = path.join(params.appModuleDir, 'build', 'outputs', 'apk');
    for (const metadataFile of findMetadataFiles(apkRoot, 0)) {
        const metadata = readMetadata(metadataFile);
        if (!metadata?.applicationId || metadata.variantName?.toLowerCase() !== params.variant.toLowerCase()) continue;
        const outputFile = metadata.elements?.[0]?.outputFile;
        return {
            applicationId: metadata.applicationId,
            apkPath: outputFile ? path.join(path.dirname(metadataFile), outputFile) : undefined,
        };
    }
    return undefined;
}

function findMetadataFiles(dir: string, depth: number): string[] {
    if (depth > MAX_SEARCH_DEPTH || !fs.existsSync(dir)) return [];
    const found: string[] = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) found.push(...findMetadataFiles(full, depth + 1));
        else if (entry.name === OUTPUT_METADATA_FILE) found.push(full);
    }
    return found;
}

function readMetadata(file: string): OutputMetadata | undefined {
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8')) as OutputMetadata;
    } catch {
        return undefined;
    }
}
