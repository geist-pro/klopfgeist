#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs `block` and returns the Objective-C exception it raised, or nil. Swift cannot catch these;
/// CoreML raises one from inside Kokoro's word-to-phoneme model on some input (crash reports of
/// 2026-11-21), which would otherwise take the whole app down in the middle of a conversion.
NSException *_Nullable MVCatchException(NS_NOESCAPE void (^block)(void));

NS_ASSUME_NONNULL_END
