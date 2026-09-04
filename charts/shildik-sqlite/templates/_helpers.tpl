{{- define "shildik.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "shildik.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "shildik.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "shildik.labels" -}}
app.kubernetes.io/name: {{ include "shildik.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "shildik.selectorLabels" -}}
app.kubernetes.io/name: {{ include "shildik.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
The image tag falls back to the chart's appVersion, so that a chart is installable without values
and still says what it runs. `latest` is deliberately not the default: a tag that moves makes two
pods of one deployment able to run different code.
*/}}
{{- define "shildik.image" -}}
{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}
{{- end -}}
