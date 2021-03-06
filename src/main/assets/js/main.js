(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngAnimate',
    'ngMaterial',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.import',
    'slicebox.seriestags',
    'slicebox.home',
    'slicebox.anonymization',
    'slicebox.transactions',
    'slicebox.log',
    'slicebox.adminPacs',
    'slicebox.adminForwarding',
    'slicebox.adminFiltering',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers',
    'slicebox.adminSeriesTypes',
    'slicebox.adminSystem'
])

.provider('sbxUtil', function() {

    this.$get = function() {
        return {
            tagPathToString: function(tagPath, format) {
                if (!tagPath) {
                    return "";
                }

                format = format === 'tags' || format === 'names' ? format : 'names';

                var tagToString = function(path) {
                    if (!path || !(path.tag || path.name)) {
                        throw Error("Invalid tag path: one of tag and name must be defined");
                    }

                    if (format === 'names' && path.name) {
                        return path.name;
                    } else {
                        var returnValue = path.tag.toString(16);

                        while (returnValue.length < 8) {
                            returnValue = '0' + returnValue;
                        }
                        returnValue = '(' + returnValue.slice(0, 4) + ',' + returnValue.slice(4) + ')';
                        return returnValue.toUpperCase();
                    }
                };

                var toTagPathString = function(path, tail) {
                    var itemIndexSuffix = "";
                    if (path.item) {
                        itemIndexSuffix = '[' + path.item + ']';
                    }
                    var tagPrefix = tagToString(path);
                    if (tagPrefix.length === 0) {
                        throw Error("Invalid tag path");
                    }
                    var head = tagPrefix + itemIndexSuffix;
                    var part = head + tail;
                    if (!path.previous) {
                        return part;
                    } else {
                        return toTagPathString(path.previous, "." + part);
                    }
                };

                try {
                    return toTagPathString(tagPath, "");
                } catch(err) {
                    return "";
                }
            }
        };
    };
})

.config(function($locationProvider, $routeProvider, $mdThemingProvider, $filterProvider, $httpProvider, sbxUtilProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey', {
            'default': '600'
        })
        .accentPalette('pink')
        .warnPalette('red');

    $mdThemingProvider.theme('redTheme')
        .primaryPalette('red');

    // Register filters
    $filterProvider.register('dicomTag', function() {
        return function(tag) {
            var returnValue = tag;

            if (angular.isDefined(tag) && angular.isNumber(tag) && tag !== 0) {
                returnValue = tag.toString(16);

                while (returnValue.length < 8) {
                    returnValue = '0' + returnValue;
                }

                returnValue = returnValue.slice(0, 4) + ',' + returnValue.slice(4);
                returnValue = returnValue.toUpperCase();
            }

            return returnValue;
        };
    });

    $filterProvider.register('tagPath', function() {
        return sbxUtilProvider.$get().tagPathToString;
    });

    $httpProvider.interceptors.push(function($q, $location) {
        return {
            'responseError': function(rejection) {
                if (rejection.status === 401 && $location.path() !== '/login') {
                    $location.path('/login');
                } else {
                    return $q.reject(rejection);
                }
            }
        };
    });
})

.filter('prettyPatientName', function () {
    return function (text) {
        return text ? text.replace(new RegExp('\\^', 'g'), ' ') : '';
    };
})

.run(function ($rootScope, $location, userService) { 
    $rootScope.$on('$locationChangeStart', function (event, next, current) {
        // redirect to login page if not logged in
        userService.currentUserPromise.then(function () {}, function () {
            if ($location.path() !== '/login') {
                $rootScope.requestedPage = current;
                $location.path('/login');
            }
        });
    });
})

.controller('SliceboxCtrl', function($scope, $http, $location, $mdSidenav, userService) {

    $scope.uiState = {};

    userService.updateCurrentUser();

    $http.get('/api/system/information').then(function (info) {
        $scope.uiState.systemInformation = info.data;
    });

    $scope.logout = function() {
        userService.logout().finally(function() {
            userService.updateCurrentUser().finally(function () {
                $location.path('/login');
            });
        });
    };

    $scope.userSignedIn = function() {
        return userService.currentUser;
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.closeLeftNav = function() {
        $mdSidenav('leftNav').close();
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };

});
