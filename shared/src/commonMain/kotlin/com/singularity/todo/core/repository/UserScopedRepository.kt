package com.singularity.todo.core.repository

/**
 * @deprecated Use [GenericUserScopedRepository] instead. This typealias
 * preserves the old qualified name in existing code. Migrate to the new
 * base interface; `UserScopedRepository` will be removed in the cleanup commit.
 */
@Deprecated(
    message = "Use GenericUserScopedRepository; UserScopedRepository will be removed in PR8",
    replaceWith = ReplaceWith(
        "GenericUserScopedRepository<E, ID>",
        "com.singularity.todo.core.repository.GenericUserScopedRepository",
    ),
)
typealias UserScopedRepository<T, ID> = GenericUserScopedRepository<T, ID>
