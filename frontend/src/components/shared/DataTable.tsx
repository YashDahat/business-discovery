import { AgGridReact } from 'ag-grid-react'
import type { ColDef, GridOptions, RowClickedEvent } from 'ag-grid-community'
import { themeQuartz, colorSchemeDark } from 'ag-grid-community'

const darkTheme = themeQuartz.withPart(colorSchemeDark).withParams({
  backgroundColor: '#111111',
  foregroundColor: '#ffffff',
  borderColor: '#1e1e1e',
  rowHoverColor: '#1a1a1a',
  selectedRowBackgroundColor: '#1e2a1e',
  headerBackgroundColor: '#0a0a0a',
  headerTextColor: '#666666',
  fontFamily: 'Inter, ui-sans-serif, sans-serif',
  fontSize: 13,
  cellTextColor: '#e5e5e5',
  accentColor: '#00ff88',
})

interface DataTableProps<T extends object> {
  rowData: T[]
  columnDefs: ColDef<T>[]
  onRowClicked?: (row: T) => void
  height?: string
  rowClickable?: boolean
}

export function DataTable<T extends object>({
  rowData,
  columnDefs,
  onRowClicked,
  height = '500px',
  rowClickable = false,
}: DataTableProps<T>) {
  const gridOptions: GridOptions<T> = {
    defaultColDef: {
      resizable: true,
      sortable: true,
      filter: false,
      flex: 1,
      minWidth: 80,
    },
    suppressCellFocus: true,
    rowClass: rowClickable ? 'cursor-pointer' : undefined,
    onRowClicked: onRowClicked
      ? (e: RowClickedEvent<T>) => { if (e.data) onRowClicked(e.data) }
      : undefined,
  }

  return (
    <div style={{ height, width: '100%' }}>
      <AgGridReact<T>
        rowData={rowData}
        columnDefs={columnDefs}
        theme={darkTheme}
        gridOptions={gridOptions}
      />
    </div>
  )
}
