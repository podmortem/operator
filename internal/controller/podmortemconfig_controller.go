/*
Copyright 2025.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controller

import (
	"bytes"
	"context"
	"encoding/json" // Added for JSON marshalling
	"fmt"
	"io"

	corev1 "k8s.io/api/core/v1"
	apierrors "k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	podmortemv1alpha1 "github.com/caevans/podmortem/api/v1alpha1"
)

// PodmortemConfigReconciler reconciles a PodmortemConfig object
type PodmortemConfigReconciler struct {
	client.Client
	Scheme    *runtime.Scheme
	Clientset kubernetes.Interface
}

// PodFailureData defines the structure for the collected pod failure information.
// This will be marshalled into JSON.
type PodFailureData struct {
	Pod    *corev1.Pod    `json:"pod"`
	Logs   string         `json:"logs"`
	Events []corev1.Event `json:"events"`
}

//+kubebuilder:rbac:groups=podmortem.redhat.com,resources=podmortemconfigs,verbs=get;list;watch;create;update;patch;delete
//+kubebuilder:rbac:groups=podmortem.redhat.com,resources=podmortemconfigs/status,verbs=get;update;patch
//+kubebuilder:rbac:groups=podmortem.redhat.com,resources=podmortemconfigs/finalizers,verbs=update
//+kubebuilder:rbac:groups=core,resources=pods,verbs=get;list;watch
//+kubebuilder:rbac:groups=core,resources=pods/log,verbs=get
//+kubebuilder:rbac:groups="",resources=events,verbs=get;list;watch

// Reconcile is part of the main kubernetes reconciliation loop which aims to
// move the current state of the cluster closer to the desired state.
// TODO(user): Modify the Reconcile function to compare the state specified by
// the PodmortemConfig object against the actual cluster state, and then
// perform operations to make the cluster state reflect the state specified by
// the user.
//
// For more details, check Reconcile and its Result here:
// - https://pkg.go.dev/sigs.k8s.io/controller-runtime@v0.18.0/pkg/reconcile
func (r *PodmortemConfigReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	log := logf.FromContext(ctx)

	pod := &corev1.Pod{}
	err := r.Get(ctx, req.NamespacedName, pod)
	if err != nil {
		if apierrors.IsNotFound(err) {
			// Request object not found, could have been deleted after reconcile request.
			// Owned objects are automatically garbage collected. For additional cleanup logic use finalizers.
			// Return and don't requeue
			return ctrl.Result{}, nil
		}
		// Error reading the object - requeue the request.
		return ctrl.Result{}, err
	}

	configs := &podmortemv1alpha1.PodmortemConfigList{}
	if err := r.List(ctx, configs); err != nil {
		log.Error(err, "unable to list PodmortemConfigs")
		return ctrl.Result{}, err
	}

	for _, config := range configs.Items {
		selector, err := metav1.LabelSelectorAsSelector(&config.Spec.PodSelector)
		if err != nil {
			log.Error(err, "error converting label selector")
			continue
		}

		if selector.Matches(labels.Set(pod.Labels)) {
			for _, containerStatus := range pod.Status.ContainerStatuses {
				if containerStatus.State.Terminated != nil && containerStatus.State.Terminated.ExitCode != 0 {
					log.Info("Pod has a container with an ungraceful exit", "pod", pod.Name, "container", containerStatus.Name, "exitCode", containerStatus.State.Terminated.ExitCode)

					// --- START: MODIFIED SECTION ---

					// 1. Get Logs
					podLogs, err := r.getPodLogs(ctx, pod, containerStatus.Name)
					if err != nil {
						log.Error(err, "failed to get pod logs")
						// We can still proceed without logs
					}

					// 2. Get Events for the Pod
					eventList := &corev1.EventList{}
					err = r.List(ctx, eventList, client.InNamespace(pod.Namespace), client.MatchingFields{"involvedObject.name": pod.Name})
					if err != nil {
						log.Error(err, "failed to get events for pod")
						// We can still proceed without events
					}

					// 3. Assemble the failure data
					failureData := PodFailureData{
						Pod:    pod,
						Logs:   podLogs,
						Events: eventList.Items,
					}

					// 4. Marshal data to JSON
					jsonData, err := json.MarshalIndent(failureData, "", "  ")
					if err != nil {
						log.Error(err, "failed to marshal failure data to JSON")
						continue
					}

					// 5. Print JSON to the log
					log.Info("Collected pod failure data", "data", string(jsonData))

					// --- END: MODIFIED SECTION ---
				}
			}
		}
	}

	return ctrl.Result{}, nil
}

func (r *PodmortemConfigReconciler) getPodLogs(ctx context.Context, pod *corev1.Pod, containerName string) (string, error) {
	podLogOpts := &corev1.PodLogOptions{
		Container: containerName,
	}
	req := r.Clientset.CoreV1().Pods(pod.Namespace).GetLogs(pod.Name, podLogOpts)
	podLogs, err := req.Stream(ctx)
	if err != nil {
		return "", fmt.Errorf("error in opening stream: %w", err)
	}
	defer podLogs.Close()

	buf := new(bytes.Buffer)
	_, err = io.Copy(buf, podLogs)
	if err != nil {
		return "", fmt.Errorf("error in copy information from podLogs to buf: %w", err)
	}
	return buf.String(), nil
}

// SetupWithManager sets up the controller with the Manager.
func (r *PodmortemConfigReconciler) SetupWithManager(mgr ctrl.Manager) error {
	// Add an index for the .spec.involvedObject.name field of Events, so we can query them efficiently.
	if err := mgr.GetFieldIndexer().IndexField(context.Background(), &corev1.Event{}, "involvedObject.name", func(rawObj client.Object) []string {
		event := rawObj.(*corev1.Event)
		return []string{event.InvolvedObject.Name}
	}); err != nil {
		return err
	}

	return ctrl.NewControllerManagedBy(mgr).
		For(&podmortemv1alpha1.PodmortemConfig{}).
		Watches(
			&corev1.Pod{},
			handler.EnqueueRequestsFromMapFunc(r.findConfigsForPod),
		).
		Named("podmortemconfig").
		Complete(r)
}

func (r *PodmortemConfigReconciler) findConfigsForPod(ctx context.Context, pod client.Object) []reconcile.Request {
	podObj, ok := pod.(*corev1.Pod)
	if !ok {
		return nil
	}

	// We only care about pods that have failed
	isFailed := false
	for _, status := range podObj.Status.ContainerStatuses {
		if status.State.Terminated != nil && status.State.Terminated.ExitCode != 0 {
			isFailed = true
			break
		}
	}
	if !isFailed && podObj.Status.Phase != corev1.PodFailed {
		return nil
	}

	configs := &podmortemv1alpha1.PodmortemConfigList{}
	if err := r.List(ctx, configs); err != nil {
		return nil
	}

	var requests []reconcile.Request
	for _, config := range configs.Items {
		selector, err := metav1.LabelSelectorAsSelector(&config.Spec.PodSelector)
		if err != nil {
			continue
		}
		if selector.Matches(labels.Set(podObj.GetLabels())) {
			requests = append(requests, reconcile.Request{
				NamespacedName: types.NamespacedName{
					Name:      podObj.GetName(),
					Namespace: podObj.GetNamespace(),
				},
			})
		}
	}
	return requests
}
