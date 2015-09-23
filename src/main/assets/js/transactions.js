(function () {
   'use strict';
}());

angular.module('slicebox.transactions', ['ngRoute'])

.config(function($routeProvider, $mdThemingProvider) {
  $routeProvider.when('/transactions', {
    templateUrl: '/assets/partials/transactions.html',
    controller: 'TransactionsCtrl'
  });
})

.controller('TransactionsCtrl', function($scope, $http, $q, sbxMisc, openTagSeriesModal) {
    $scope.uiState = {};

    $scope.openTagSeriesModalFunction = function() {
        return function(inboxEntries) {
            var inboxEntryIds = inboxEntries.map(function (inboxEntry) { return inboxEntry.id; });
            var imagesPromises = inboxEntryIds.map(function (inboxEntryId) { return $http.get('/api/inbox/' + inboxEntryId + "/images").then(function (imagesData) { return imagesData.data; }); });
            var imagesPromise = $q.all(imagesPromises).then(function (listOfImageLists) { return sbxMisc.flatten(listOfImageLists); });
            var seriesIdsPromise = imagesPromise.then(function (images) { return images.map(function (image) { return image.seriesId; }); });
            var uniqueSeriesIdsPromise = seriesIdsPromise.then(function (seriesIds) { return sbxMisc.unique(seriesIds); });

            return openTagSeriesModal(uniqueSeriesIdsPromise);
        };
    };

})

.controller('InboxCtrl', function($scope, $http, $interval, $mdDialog) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/inbox/', 'inbox entries')
            },
            {
                name: 'Tag Series',
                action: $scope.openTagSeriesModalFunction()
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.inboxTable)) {
            $scope.callbacks.inboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadInboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/inbox');
    };

})

.controller('OutboxCtrl', function($scope, $http, $q, $interval, openConfirmActionModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteEntities
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outboxTable)) {
            $scope.callbacks.outboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };

    $scope.convertOutboxPageData = function(outboxEntries) {
        var outboxDataCollector = {};
        var outboxTransactionData;
        var pageData = [];

        var id = 1;
        angular.forEach(outboxEntries, function(outboxEntry) {
            outboxTransactionData = outboxDataCollector[outboxEntry.transactionId];
            if (angular.isUndefined(outboxTransactionData)) {
                outboxTransactionData =
                    {
                        id: id, 
                        remoteBoxName: outboxEntry.remoteBoxName,
                        totalImageCount: outboxEntry.totalImageCount,
                        failed: outboxEntry.failed,
                        outboxEntryIds: [],
                        imagesLeft: 0
                    };

                outboxDataCollector[outboxEntry.transactionId] = outboxTransactionData;
                id = id + 1;
            }

            outboxTransactionData.outboxEntryIds.push(outboxEntry.id);
            outboxTransactionData.imagesLeft = outboxTransactionData.imagesLeft + 1;
        });

        angular.forEach(outboxDataCollector, function(outboxTransactionData) {
            pageData.push(outboxTransactionData);
        });

        return pageData;
    };

    $scope.calculateProgress = function(outboxTransactionData) {
        var data = outboxTransactionData;
        return Math.round(100 * (data.totalImageCount - data.imagesLeft) / data.totalImageCount);
    };

    // private functions

    function confirmDeleteEntities(entities) {
        var deleteConfirmationText = 'Permanently delete ' + entities.length + ' transaction(s)?';

        return openConfirmActionModal('Delete transaction(s)', deleteConfirmationText, 'Delete', function() {
            return deleteEntities(entities);
        });
    }

    function deleteEntities(entities) {
        var deletePromises = [];
        var deletePromise;
        var deleteAllPromises;

        angular.forEach(entities, function(entity) {
            for (var i = 0; i < entity.outboxEntryIds.length; i++) {
                deletePromise = $http.delete('/api/outbox/' + entity.outboxEntryIds[i]);
                deletePromises.push(deletePromise);
            }
        });

        deleteAllPromises = $q.all(deletePromises);

        deleteAllPromises.then(function() {
            $scope.showInfoMessage(entities.length + " transaction(s) deleted");
        }, function(response) {
            $scope.showErrorMessage(response.data);
        });

        return deleteAllPromises;
    }

})

.controller('SentCtrl', function($scope, $http, $interval, $mdDialog) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/sent/', 'sent entries')
            },
            {
                name: 'Tag Series',
                action: $scope.openTagSeriesModalFunction()
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.sentTable)) {
            $scope.callbacks.sentTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    $scope.loadSentPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/sent');
    };

})

.controller('BoxLogCtrl', function($scope, $http, $interval) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/log/', 'log message(s)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.logTable)) {
            $scope.callbacks.logTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        return $http.get('/api/log?startindex=' + startIndex + '&count=' + count + '&subject=Box');
    };

});