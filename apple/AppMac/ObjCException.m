#import "ObjCException.h"

NSException *_Nullable MVCatchException(NS_NOESCAPE void (^block)(void)) {
  @try {
    block();
    return nil;
  } @catch (NSException *exception) {
    return exception;
  }
}
