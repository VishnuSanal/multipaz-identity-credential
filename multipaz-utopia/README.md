# multipaz-utopia

Utopia is the fictional world used across Multipaz demos and sample applications. This module contains shared document types, transaction types, and known-type extensions used by all Utopia-themed organizations.

## Module Structure

```
multipaz-utopia/
└── src/commonMain/kotlin/org/multipaz/utopia/
    └── knowntypes/          # Shared document & transaction type definitions
        ├── DocumentTypeRepositoryExt.kt
        ├── BreweryPurchaseTransaction.kt
        └── ...              # Other Utopia document types (boarding pass, movie ticket, etc.)
```

## Organizations

The self-contained Utopia demo applications (Brewery and others) have moved to their own
repository: [multipaz-utopia](https://github.com/openwallet-foundation/multipaz-utopia).
They consume this module's shared document types as a published dependency.

## Shared Known Types

The `knowntypes` package registers all Utopia document and transaction types into the `DocumentTypeRepository`. Call `DocumentTypeRepository.addUtopiaTypes()` in any server or app that needs them.

Currently registered types:

- `UtopiaBoardingPass`
- `UtopiaMovieTicket`
- `UtopiaNaturalization`
- `BreweryPurchaseTransaction`

## Adding a New Document or Transaction Type

Define new types in `src/commonMain/kotlin/org/multipaz/utopia/knowntypes/` and register them in
`DocumentTypeRepositoryExt.kt`. New demo organizations that consume these types live in the
[multipaz-utopia](https://github.com/openwallet-foundation/multipaz-utopia) repository.
