export function variantTaskName(action: 'assemble' | 'install', variant: string): string {
    return `${action}${variant.charAt(0).toUpperCase()}${variant.substring(1)}`;
}

export function qualifiedTask(gradlePath: string, task: string): string {
    return `${gradlePath}:${task}`;
}
